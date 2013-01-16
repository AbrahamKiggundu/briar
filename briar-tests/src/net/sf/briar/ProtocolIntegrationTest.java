package net.sf.briar;

import static net.sf.briar.api.transport.TransportConstants.TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import net.sf.briar.api.ContactId;
import net.sf.briar.api.crypto.CryptoComponent;
import net.sf.briar.api.protocol.Ack;
import net.sf.briar.api.protocol.Author;
import net.sf.briar.api.protocol.AuthorFactory;
import net.sf.briar.api.protocol.Group;
import net.sf.briar.api.protocol.GroupFactory;
import net.sf.briar.api.protocol.GroupId;
import net.sf.briar.api.protocol.Message;
import net.sf.briar.api.protocol.MessageFactory;
import net.sf.briar.api.protocol.MessageId;
import net.sf.briar.api.protocol.MessageVerifier;
import net.sf.briar.api.protocol.Offer;
import net.sf.briar.api.protocol.PacketFactory;
import net.sf.briar.api.protocol.ProtocolReader;
import net.sf.briar.api.protocol.ProtocolReaderFactory;
import net.sf.briar.api.protocol.ProtocolWriter;
import net.sf.briar.api.protocol.ProtocolWriterFactory;
import net.sf.briar.api.protocol.Request;
import net.sf.briar.api.protocol.SubscriptionUpdate;
import net.sf.briar.api.protocol.Transport;
import net.sf.briar.api.protocol.TransportId;
import net.sf.briar.api.protocol.TransportUpdate;
import net.sf.briar.api.protocol.UnverifiedMessage;
import net.sf.briar.api.transport.ConnectionContext;
import net.sf.briar.api.transport.ConnectionReader;
import net.sf.briar.api.transport.ConnectionReaderFactory;
import net.sf.briar.api.transport.ConnectionWriter;
import net.sf.briar.api.transport.ConnectionWriterFactory;
import net.sf.briar.clock.ClockModule;
import net.sf.briar.crypto.CryptoModule;
import net.sf.briar.db.DatabaseModule;
import net.sf.briar.lifecycle.LifecycleModule;
import net.sf.briar.protocol.ProtocolModule;
import net.sf.briar.protocol.duplex.DuplexProtocolModule;
import net.sf.briar.protocol.simplex.SimplexProtocolModule;
import net.sf.briar.serial.SerialModule;
import net.sf.briar.transport.TransportModule;

import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;

public class ProtocolIntegrationTest extends BriarTestCase {

	private final ConnectionReaderFactory connectionReaderFactory;
	private final ConnectionWriterFactory connectionWriterFactory;
	private final ProtocolReaderFactory protocolReaderFactory;
	private final ProtocolWriterFactory protocolWriterFactory;
	private final PacketFactory packetFactory;
	private final MessageVerifier messageVerifier;

	private final ContactId contactId;
	private final TransportId transportId;
	private final byte[] secret;
	private final Author author;
	private final Group group, group1;
	private final Message message, message1, message2, message3;
	private final String authorName = "Alice";
	private final String subject = "Hello";
	private final String messageBody = "Hello world";
	private final Collection<MessageId> messageIds;
	private final Collection<Transport> transports;
	private final long timestamp = System.currentTimeMillis();

	public ProtocolIntegrationTest() throws Exception {
		super();
		Injector i = Guice.createInjector(new TestDatabaseModule(),
				new ClockModule(), new CryptoModule(), new DatabaseModule(),
				new LifecycleModule(), new ProtocolModule(),
				new DuplexProtocolModule(), new SimplexProtocolModule(),
				new SerialModule(), new TransportModule());
		connectionReaderFactory = i.getInstance(ConnectionReaderFactory.class);
		connectionWriterFactory = i.getInstance(ConnectionWriterFactory.class);
		protocolReaderFactory = i.getInstance(ProtocolReaderFactory.class);
		protocolWriterFactory = i.getInstance(ProtocolWriterFactory.class);
		packetFactory = i.getInstance(PacketFactory.class);
		messageVerifier = i.getInstance(MessageVerifier.class);
		contactId = new ContactId(234);
		transportId = new TransportId(TestUtils.getRandomId());
		// Create a shared secret
		secret = new byte[32];
		new Random().nextBytes(secret);
		// Create two groups: one restricted, one unrestricted
		GroupFactory groupFactory = i.getInstance(GroupFactory.class);
		group = groupFactory.createGroup("Unrestricted group", null);
		CryptoComponent crypto = i.getInstance(CryptoComponent.class);
		KeyPair groupKeyPair = crypto.generateSignatureKeyPair();
		group1 = groupFactory.createGroup("Restricted group",
				groupKeyPair.getPublic().getEncoded());
		// Create an author
		AuthorFactory authorFactory = i.getInstance(AuthorFactory.class);
		KeyPair authorKeyPair = crypto.generateSignatureKeyPair();
		author = authorFactory.createAuthor(authorName,
				authorKeyPair.getPublic().getEncoded());
		// Create two messages to each group: one anonymous, one pseudonymous
		MessageFactory messageFactory = i.getInstance(MessageFactory.class);
		message = messageFactory.createMessage(null, group, subject,
				messageBody.getBytes("UTF-8"));
		message1 = messageFactory.createMessage(null, group1,
				groupKeyPair.getPrivate(), subject,
				messageBody.getBytes("UTF-8"));
		message2 = messageFactory.createMessage(null, group, author,
				authorKeyPair.getPrivate(), subject,
				messageBody.getBytes("UTF-8"));
		message3 = messageFactory.createMessage(null, group1,
				groupKeyPair.getPrivate(), author, authorKeyPair.getPrivate(),
				subject, messageBody.getBytes("UTF-8"));
		messageIds = Arrays.asList(message.getId(),
				message1.getId(), message2.getId(), message3.getId());
		// Create some transports
		TransportId transportId = new TransportId(TestUtils.getRandomId());
		Transport transport = new Transport(transportId,
				Collections.singletonMap("bar", "baz"));
		transports = Collections.singletonList(transport);
	}

	@Test
	public void testWriteAndRead() throws Exception {
		read(write());
	}

	private byte[] write() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ConnectionContext ctx = new ConnectionContext(contactId, transportId,
				secret.clone(), 0L, true);
		ConnectionWriter conn = connectionWriterFactory.createConnectionWriter(
				out, Long.MAX_VALUE, ctx, false, true);
		OutputStream out1 = conn.getOutputStream();
		ProtocolWriter writer = protocolWriterFactory.createProtocolWriter(out1,
				false);

		Ack a = packetFactory.createAck(messageIds);
		writer.writeAck(a);

		writer.writeMessage(message.getSerialised());
		writer.writeMessage(message1.getSerialised());
		writer.writeMessage(message2.getSerialised());
		writer.writeMessage(message3.getSerialised());

		Offer o = packetFactory.createOffer(messageIds);
		writer.writeOffer(o);

		BitSet requested = new BitSet(4);
		requested.set(1);
		requested.set(3);
		Request r = packetFactory.createRequest(requested, 4);
		writer.writeRequest(r);

		// Use a LinkedHashMap for predictable iteration order
		Map<Group, Long> subs = new LinkedHashMap<Group, Long>();
		subs.put(group, 0L);
		subs.put(group1, 0L);
		SubscriptionUpdate s = packetFactory.createSubscriptionUpdate(
				Collections.<GroupId, GroupId>emptyMap(), subs, 0L, timestamp);
		writer.writeSubscriptionUpdate(s);

		TransportUpdate t = packetFactory.createTransportUpdate(transports,
				timestamp);
		writer.writeTransportUpdate(t);

		writer.flush();
		return out.toByteArray();
	}

	private void read(byte[] connectionData) throws Exception {
		InputStream in = new ByteArrayInputStream(connectionData);
		byte[] tag = new byte[TAG_LENGTH];
		assertEquals(TAG_LENGTH, in.read(tag, 0, TAG_LENGTH));
		// FIXME: Check that the expected tag was received
		ConnectionContext ctx = new ConnectionContext(contactId, transportId,
				secret.clone(), 0L, false);
		ConnectionReader conn = connectionReaderFactory.createConnectionReader(
				in, ctx, true, true);
		InputStream in1 = conn.getInputStream();
		ProtocolReader reader = protocolReaderFactory.createProtocolReader(in1);

		// Read the ack
		assertTrue(reader.hasAck());
		Ack a = reader.readAck();
		assertEquals(messageIds, a.getMessageIds());

		// Read and verify the messages
		assertTrue(reader.hasMessage());
		UnverifiedMessage m = reader.readMessage();
		checkMessageEquality(message, messageVerifier.verifyMessage(m));
		assertTrue(reader.hasMessage());
		m = reader.readMessage();
		checkMessageEquality(message1, messageVerifier.verifyMessage(m));
		assertTrue(reader.hasMessage());
		m = reader.readMessage();
		checkMessageEquality(message2, messageVerifier.verifyMessage(m));
		assertTrue(reader.hasMessage());
		m = reader.readMessage();
		checkMessageEquality(message3, messageVerifier.verifyMessage(m));

		// Read the offer
		assertTrue(reader.hasOffer());
		Offer o = reader.readOffer();
		assertEquals(messageIds, o.getMessageIds());

		// Read the request
		assertTrue(reader.hasRequest());
		Request req = reader.readRequest();
		BitSet requested = req.getBitmap();
		assertFalse(requested.get(0));
		assertTrue(requested.get(1));
		assertFalse(requested.get(2));
		assertTrue(requested.get(3));
		// If there are any padding bits, they should all be zero
		assertEquals(2, requested.cardinality());

		// Read the subscription update
		assertTrue(reader.hasSubscriptionUpdate());
		SubscriptionUpdate s = reader.readSubscriptionUpdate();
		Map<Group, Long> subs = s.getSubscriptions();
		assertEquals(2, subs.size());
		assertEquals(Long.valueOf(0L), subs.get(group));
		assertEquals(Long.valueOf(0L), subs.get(group1));
		assertTrue(s.getTimestamp() == timestamp);

		// Read the transport update
		assertTrue(reader.hasTransportUpdate());
		TransportUpdate t = reader.readTransportUpdate();
		assertEquals(transports, t.getTransports());
		assertTrue(t.getTimestamp() == timestamp);

		in.close();
	}

	private void checkMessageEquality(Message m1, Message m2) {
		assertEquals(m1.getId(), m2.getId());
		assertEquals(m1.getParent(), m2.getParent());
		assertEquals(m1.getGroup(), m2.getGroup());
		assertEquals(m1.getAuthor(), m2.getAuthor());
		assertEquals(m1.getTimestamp(), m2.getTimestamp());
		assertArrayEquals(m1.getSerialised(), m2.getSerialised());
	}
}
