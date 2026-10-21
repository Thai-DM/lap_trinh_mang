package com.smashprofs.server.network;
import java.io.*;

/** Private owned bytes shared between writers; implementations never expose their array. */
interface SharedFrame { int size(); void writeTo(OutputStream output) throws IOException; }
