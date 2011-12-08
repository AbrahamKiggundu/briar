package net.sf.briar.protocol.batch;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import net.sf.briar.api.transport.BatchTransportWriter;

class TestBatchTransportWriter implements BatchTransportWriter {

	private final ByteArrayOutputStream out;
	private final long capacity;

	private boolean disposed = false, exception = false;

	TestBatchTransportWriter(ByteArrayOutputStream out, long capacity) {
		this.out = out;
		this.capacity = capacity;
	}

	public long getCapacity() {
		return capacity;
	}

	public OutputStream getOutputStream() {
		return out;
	}

	public void dispose(boolean exception) {
		assert !disposed;
		disposed = true;
		this.exception = exception;
	}

	boolean getDisposed() {
		return disposed;
	}

	boolean getException() {
		return exception;
	}
}