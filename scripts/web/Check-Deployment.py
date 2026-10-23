"""Check the local HTTPS proxy, exact release, and real WebSocket handshake."""
import base64
import http.client
import hashlib
import json
import os
import socket
import ssl
import sys
import struct


class LocalHTTPS(http.client.HTTPSConnection):
    def connect(self):
        self.sock = self._context.wrap_socket(
            socket.create_connection(("127.0.0.1", 443), timeout=self.timeout),
            server_hostname=self.host,
        )


def send_frame(sock, opcode, payload):
    mask = os.urandom(4)
    length = len(payload)
    assert length < 65536
    header = bytes([0x80 | opcode, 0x80 | length]) if length < 126 else bytes([0x80 | opcode, 0xfe]) + struct.pack('!H', length)
    sock.sendall(header + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(payload)))


def receive_frame(stream):
    def exact(size):
        data = stream.read(size)
        if len(data) != size:
            raise RuntimeError('WebSocket ended before CONNECT_ACK')
        return data
    first, second = exact(2)
    assert first & 0xf0 == 0x80 and not second & 0x80, 'Invalid server frame'
    length = second & 0x7f
    if length == 126:
        length = struct.unpack('!H', exact(2))[0]
    elif length == 127:
        length = struct.unpack('!Q', exact(8))[0]
    assert length <= 1048576, 'Oversized frame'
    return first & 0xf, exact(length)


def check(domain, release):
    connection = LocalHTTPS(domain, timeout=8, context=ssl.create_default_context())
    try:
        connection.request("GET", "/version.json", headers={"Cache-Control": "no-cache"})
        response = connection.getresponse()
        assert response.status == 200, f"Website HTTP {response.status}"
        assert json.loads(response.read())["release"] == release, "Wrong website release"
    finally:
        connection.close()

    connection = LocalHTTPS(domain, timeout=8, context=ssl.create_default_context())
    try:
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        connection.request("GET", "/ws", headers={
            "Connection": "Upgrade", "Upgrade": "websocket",
            "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": key,
            "Origin": "https://" + domain,
        })
        response = connection.getresponse()
        assert response.status == 101, f"WebSocket HTTP {response.status}"
        accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode('ascii')).digest()).decode('ascii')
        assert response.getheader('Sec-WebSocket-Accept') == accept, 'Invalid WebSocket handshake'
        request_id = 'deploy-health'
        send_frame(connection.sock, 1, json.dumps({
            'version': 1, 'type': 'CONNECT', 'requestId': request_id,
            'payload': {'gameplay': 'fighter-v1'},
        }).encode('utf-8'))
        for _ in range(8):
            opcode, payload = receive_frame(response.fp)
            if opcode == 9:
                send_frame(connection.sock, 10, payload)
                continue
            assert opcode == 1, 'WebSocket closed or sent non-text before CONNECT_ACK'
            message = json.loads(payload)
            assert message['type'] == 'CONNECT_ACK', f"Unexpected server response: {message['type']}"
            assert message['version'] == 1 and message['requestId'] == request_id
            assert message['payload']['playerId'], 'Missing authoritative player ID'
            break
        else:
            raise RuntimeError('Missing CONNECT_ACK')
        send_frame(connection.sock, 8, struct.pack('!H', 1000))
    finally:
        connection.close()


if __name__ == "__main__":
    check(*sys.argv[1:])
    print("HTTPS, release, WSS and Java CONNECT_ACK checks passed")
