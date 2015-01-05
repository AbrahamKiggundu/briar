package org.briarproject.crypto;

import static org.briarproject.api.transport.TransportConstants.AAD_LENGTH;
import static org.briarproject.api.transport.TransportConstants.HEADER_LENGTH;
import static org.briarproject.api.transport.TransportConstants.IV_LENGTH;
import static org.briarproject.api.transport.TransportConstants.MAC_LENGTH;
import static org.briarproject.api.transport.TransportConstants.MAX_FRAME_LENGTH;

import java.io.ByteArrayInputStream;

import org.briarproject.BriarTestCase;
import org.briarproject.TestLifecycleModule;
import org.briarproject.TestSystemModule;
import org.briarproject.api.FormatException;
import org.briarproject.api.crypto.AuthenticatedCipher;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.SecretKey;
import org.briarproject.util.ByteUtils;
import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;

public class StreamDecrypterImplTest extends BriarTestCase {

	// FIXME: This is an integration test, not a unit test

	private static final int MAX_PAYLOAD_LENGTH =
			MAX_FRAME_LENGTH - HEADER_LENGTH - MAC_LENGTH;

	private final CryptoComponent crypto;
	private final AuthenticatedCipher frameCipher;
	private final SecretKey frameKey;

	public StreamDecrypterImplTest() {
		Injector i = Guice.createInjector(new CryptoModule(),
				new TestLifecycleModule(), new TestSystemModule());
		crypto = i.getInstance(CryptoComponent.class);
		frameCipher = crypto.getFrameCipher();
		frameKey = crypto.generateSecretKey();
	}

	@Test
	public void testReadValidFrames() throws Exception {
		// Generate two valid frames
		byte[] frame = generateFrame(0, MAX_FRAME_LENGTH, 123, false, false);
		byte[] frame1 = generateFrame(1, MAX_FRAME_LENGTH, 123, false, false);
		// Concatenate the frames
		byte[] valid = new byte[MAX_FRAME_LENGTH * 2];
		System.arraycopy(frame, 0, valid, 0, MAX_FRAME_LENGTH);
		System.arraycopy(frame1, 0, valid, MAX_FRAME_LENGTH, MAX_FRAME_LENGTH);
		// Read the frames
		ByteArrayInputStream in = new ByteArrayInputStream(valid);
		StreamDecrypterImpl i = new StreamDecrypterImpl(in, frameCipher,
				frameKey);
		byte[] payload = new byte[MAX_PAYLOAD_LENGTH];
		assertEquals(123, i.readFrame(payload));
		assertEquals(123, i.readFrame(payload));
	}

	@Test
	public void testTruncatedFrameThrowsException() throws Exception {
		// Generate a valid frame
		byte[] frame = generateFrame(0, MAX_FRAME_LENGTH, 123, false, false);
		// Chop off the last byte
		byte[] truncated = new byte[MAX_FRAME_LENGTH - 1];
		System.arraycopy(frame, 0, truncated, 0, MAX_FRAME_LENGTH - 1);
		// Try to read the frame, which should fail due to truncation
		ByteArrayInputStream in = new ByteArrayInputStream(truncated);
		StreamDecrypterImpl i = new StreamDecrypterImpl(in, frameCipher,
				frameKey);
		try {
			i.readFrame(new byte[MAX_PAYLOAD_LENGTH]);
			fail();
		} catch(FormatException expected) {}
	}

	@Test
	public void testModifiedFrameThrowsException() throws Exception {
		// Generate a valid frame
		byte[] frame = generateFrame(0, MAX_FRAME_LENGTH, 123, false, false);
		// Modify a randomly chosen byte of the frame
		frame[(int) (Math.random() * MAX_FRAME_LENGTH)] ^= 1;
		// Try to read the frame, which should fail due to modification
		ByteArrayInputStream in = new ByteArrayInputStream(frame);
		StreamDecrypterImpl i = new StreamDecrypterImpl(in, frameCipher,
				frameKey);
		try {
			i.readFrame(new byte[MAX_PAYLOAD_LENGTH]);
			fail();
		} catch(FormatException expected) {}
	}

	@Test
	public void testShortNonFinalFrameThrowsException() throws Exception {
		// Generate a short non-final frame
		byte[] frame = generateFrame(0, MAX_FRAME_LENGTH - 1, 123, false,
				false);
		// Try to read the frame, which should fail due to invalid length
		ByteArrayInputStream in = new ByteArrayInputStream(frame);
		StreamDecrypterImpl i = new StreamDecrypterImpl(in, frameCipher,
				frameKey);
		try {
			i.readFrame(new byte[MAX_PAYLOAD_LENGTH]);
			fail();
		} catch(FormatException expected) {}
	}

	@Test
	public void testShortFinalFrameDoesNotThrowException() throws Exception {
		// Generate a short final frame
		byte[] frame = generateFrame(0, MAX_FRAME_LENGTH - 1, 123, true, false);
		// Read the frame
		ByteArrayInputStream in = new ByteArrayInputStream(frame);
		StreamDecrypterImpl i = new StreamDecrypterImpl(in, frameCipher,
				frameKey);
		int length = i.readFrame(new byte[MAX_PAYLOAD_LENGTH]);
		assertEquals(123, length);
	}

	@Test
	public void testInvalidPayloadLengthThrowsException() throws Exception {
		// Generate a frame with an invalid payload length
		byte[] frame = generateFrame(0, MAX_FRAME_LENGTH, 123, false, false);
		ByteUtils.writeUint16(MAX_PAYLOAD_LENGTH + 1, frame, 0);
		// Try to read the frame, which should fail due to invalid length
		ByteArrayInputStream in = new ByteArrayInputStream(frame);
		StreamDecrypterImpl i = new StreamDecrypterImpl(in, frameCipher,
				frameKey);
		try {
			i.readFrame(new byte[MAX_PAYLOAD_LENGTH]);
			fail();
		} catch(FormatException expected) {}
	}

	@Test
	public void testNonZeroPaddingThrowsException() throws Exception {
		// Generate a frame with bad padding
		byte[] frame = generateFrame(0, MAX_FRAME_LENGTH, 123, false, true);
		// Try to read the frame, which should fail due to bad padding
		ByteArrayInputStream in = new ByteArrayInputStream(frame);
		StreamDecrypterImpl i = new StreamDecrypterImpl(in, frameCipher,
				frameKey);
		try {
			i.readFrame(new byte[MAX_PAYLOAD_LENGTH]);
			fail();
		} catch(FormatException expected) {}
	}

	@Test
	public void testCannotReadBeyondFinalFrame() throws Exception {
		// Generate a valid final frame and another valid final frame after it
		byte[] frame = generateFrame(0, MAX_FRAME_LENGTH, MAX_PAYLOAD_LENGTH,
				true, false);
		byte[] frame1 = generateFrame(1, MAX_FRAME_LENGTH, 123, true, false);
		// Concatenate the frames
		byte[] extraFrame = new byte[MAX_FRAME_LENGTH * 2];
		System.arraycopy(frame, 0, extraFrame, 0, MAX_FRAME_LENGTH);
		System.arraycopy(frame1, 0, extraFrame, MAX_FRAME_LENGTH,
				MAX_FRAME_LENGTH);
		// Read the final frame, which should first read the tag
		ByteArrayInputStream in = new ByteArrayInputStream(extraFrame);
		StreamDecrypterImpl i = new StreamDecrypterImpl(in, frameCipher,
				frameKey);
		byte[] payload = new byte[MAX_PAYLOAD_LENGTH];
		assertEquals(MAX_PAYLOAD_LENGTH, i.readFrame(payload));
		// The frame after the final frame should not be read
		assertEquals(-1, i.readFrame(payload));
	}

	private byte[] generateFrame(long frameNumber, int frameLength,
			int payloadLength, boolean finalFrame, boolean badPadding)
					throws Exception {
		byte[] iv = new byte[IV_LENGTH], aad = new byte[AAD_LENGTH];
		byte[] plaintext = new byte[frameLength - MAC_LENGTH];
		byte[] ciphertext = new byte[frameLength];
		FrameEncoder.encodeIv(iv, frameNumber);
		FrameEncoder.encodeAad(aad, frameNumber, plaintext.length);
		frameCipher.init(true, frameKey, iv, aad);
		FrameEncoder.encodeHeader(plaintext, finalFrame, payloadLength);
		if(badPadding) plaintext[HEADER_LENGTH + payloadLength] = 1;
		frameCipher.doFinal(plaintext, 0, plaintext.length, ciphertext, 0);
		return ciphertext;
	}
}
