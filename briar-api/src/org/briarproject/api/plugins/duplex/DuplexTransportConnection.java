package org.briarproject.api.plugins.duplex;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * An interface for reading and writing data over a duplex transport. The
 * connection is not responsible for encrypting/decrypting or authenticating
 * the data.
 */
public interface DuplexTransportConnection {

	/** Returns the maximum frame length of the transport in bytes. */
	int getMaxFrameLength();

	/** Returns the maximum latency of the transport in milliseconds. */
	long getMaxLatency();

	/** Returns an input stream for reading from the connection. */
	InputStream getInputStream() throws IOException;

	/** Returns an output stream for writing to the connection. */
	OutputStream getOutputStream() throws IOException;

	/**
	 * Closes the connection and disposes of any associated resources. The
	 * first argument indicates whether the connection is being closed because
	 * of an exception and the second argument indicates whether the connection
	 * was recognised, which may affect how resources are disposed of.
	 */
	void dispose(boolean exception, boolean recognised) throws IOException;
}