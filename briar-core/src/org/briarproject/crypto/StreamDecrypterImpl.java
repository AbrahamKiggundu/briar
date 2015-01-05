package org.briarproject.crypto;

import static org.briarproject.api.transport.TransportConstants.AAD_LENGTH;
import static org.briarproject.api.transport.TransportConstants.HEADER_LENGTH;
import static org.briarproject.api.transport.TransportConstants.IV_LENGTH;
import static org.briarproject.api.transport.TransportConstants.MAC_LENGTH;
import static org.briarproject.api.transport.TransportConstants.MAX_FRAME_LENGTH;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

import org.briarproject.api.FormatException;
import org.briarproject.api.crypto.AuthenticatedCipher;
import org.briarproject.api.crypto.SecretKey;
import org.briarproject.api.crypto.StreamDecrypter;

class StreamDecrypterImpl implements StreamDecrypter {

	private final InputStream in;
	private final AuthenticatedCipher frameCipher;
	private final SecretKey frameKey;
	private final byte[] iv, aad, plaintext, ciphertext;

	private long frameNumber;
	private boolean finalFrame;

	StreamDecrypterImpl(InputStream in, AuthenticatedCipher frameCipher,
			SecretKey frameKey) {
		this.in = in;
		this.frameCipher = frameCipher;
		this.frameKey = frameKey;
		iv = new byte[IV_LENGTH];
		aad = new byte[AAD_LENGTH];
		plaintext = new byte[MAX_FRAME_LENGTH - MAC_LENGTH];
		ciphertext = new byte[MAX_FRAME_LENGTH];
		frameNumber = 0;
		finalFrame = false;
	}

	public int readFrame(byte[] payload) throws IOException {
		if(finalFrame) return -1;
		// Read the frame
		int ciphertextLength = 0;
		while(ciphertextLength < MAX_FRAME_LENGTH) {
			int read = in.read(ciphertext, ciphertextLength,
					MAX_FRAME_LENGTH - ciphertextLength);
			if(read == -1) break; // We'll check the length later
			ciphertextLength += read;
		}
		int plaintextLength = ciphertextLength - MAC_LENGTH;
		if(plaintextLength < HEADER_LENGTH) throw new EOFException();
		// Decrypt and authenticate the frame
		FrameEncoder.encodeIv(iv, frameNumber);
		FrameEncoder.encodeAad(aad, frameNumber, plaintextLength);
		try {
			frameCipher.init(false, frameKey, iv, aad);
			int decrypted = frameCipher.doFinal(ciphertext, 0, ciphertextLength,
					plaintext, 0);
			if(decrypted != plaintextLength) throw new RuntimeException();
		} catch(GeneralSecurityException e) {
			throw new FormatException();
		}
		// Decode and validate the header
		finalFrame = FrameEncoder.isFinalFrame(plaintext);
		if(!finalFrame && ciphertextLength < MAX_FRAME_LENGTH)
			throw new FormatException();
		int payloadLength = FrameEncoder.getPayloadLength(plaintext);
		if(payloadLength > plaintextLength - HEADER_LENGTH)
			throw new FormatException();
		// If there's any padding it must be all zeroes
		for(int i = HEADER_LENGTH + payloadLength; i < plaintextLength; i++) {
			if(plaintext[i] != 0) throw new FormatException();
		}
		frameNumber++;
		// Copy the payload
		System.arraycopy(plaintext, HEADER_LENGTH, payload, 0, payloadLength);
		return payloadLength;
	}
}