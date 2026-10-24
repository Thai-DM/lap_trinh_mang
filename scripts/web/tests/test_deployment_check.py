"""Exercise deployment health checks against a real TLS/HTTP/WebSocket endpoint."""
import base64
import hashlib
import http.server
import importlib.util
import json
import os
import pathlib
import shutil
import ssl
import struct
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('deployment_check', pathlib.Path(__file__).parents[1] / 'Check-Deployment.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class Endpoint(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == '/version.json':
            payload = json.dumps({'release': 'expected-release'}).encode()
            self.send_response(200)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_response(101)
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        key = self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
        self.send_header('Sec-WebSocket-Accept', base64.b64encode(hashlib.sha1(key.encode()).digest()).decode())
        self.end_headers()
        if self.server.reject_origin:
            self.wfile.write(b'\x88\x02\x03\xf0')
            self.wfile.flush()
            self.close_connection = True
            return
        first, size = self.rfile.read(2)
        assert first == 0x81 and size & 0x80
        size &= 0x7f
        if size == 126:
            size = struct.unpack('!H', self.rfile.read(2))[0]
        mask = self.rfile.read(4)
        message = json.loads(bytes(c ^ mask[i % 4] for i, c in enumerate(self.rfile.read(size))))
        assert self.headers['Origin'] == 'https://localhost'
        assert message['type'] == 'CONNECT' and message['payload']['gameplay'] == 'fighter-v1'
        payload = json.dumps({'version': 1, 'type': 'CONNECT_ACK',
                              'requestId': 'wrong' if self.server.wrong_ack else message['requestId'],
                              'payload': {'playerId': 'actual-server-id'}}).encode()
        header = bytes([0x81, len(payload)]) if len(payload) < 126 else b'\x81\x7e' + struct.pack('!H', len(payload))
        self.wfile.write(header + payload)
        self.wfile.flush()
        self.rfile.read(8)  # masked normal close, or EOF when the check rejects an ACK
        self.close_connection = True


class DeploymentCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        openssl = os.environ.get('OPENSSL_BINARY') or shutil.which('openssl')
        if not openssl:
            raise unittest.SkipTest('OpenSSL executable required to create an isolated test certificate')
        cls.tmp = tempfile.TemporaryDirectory()
        cls.cert = str(pathlib.Path(cls.tmp.name) / 'cert.pem')
        key = str(pathlib.Path(cls.tmp.name) / 'key.pem')
        subprocess.run([openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                        '-keyout', key, '-out', cls.cert, '-days', '1', '-subj', '/CN=localhost',
                        '-addext', 'subjectAltName=DNS:localhost'], check=True, capture_output=True)
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Endpoint)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cls.cert, key)
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.tmp.cleanup()

    def setUp(self):
        self.server.reject_origin = False
        self.server.wrong_ack = False
        original_connect = check.socket.create_connection
        original_context = ssl.create_default_context
        self.addCleanup(patch.stopall)
        patch.object(check.socket, 'create_connection', side_effect=lambda address, **kwargs:
                     original_connect(('127.0.0.1', self.server.server_port), **kwargs)).start()
        patch.object(check.ssl, 'create_default_context', side_effect=lambda:
                     original_context(cafile=self.cert)).start()

    def test_https_release_and_real_connect_ack(self):
        check.check('localhost', 'expected-release')

    def test_rejects_old_release(self):
        with self.assertRaisesRegex(AssertionError, 'Wrong website release'):
            check.check('localhost', 'stale-release')

    def test_rejects_origin_close_after_http_upgrade(self):
        self.server.reject_origin = True
        with self.assertRaises((AssertionError, ConnectionError, RuntimeError)):
            check.check('localhost', 'expected-release')

    def test_rejects_uncorrelated_connect_ack(self):
        self.server.wrong_ack = True
        with self.assertRaises(AssertionError):
            check.check('localhost', 'expected-release')


if __name__ == '__main__':
    unittest.main()
