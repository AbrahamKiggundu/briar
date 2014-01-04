package net.sf.briar.api.db.event;

import net.sf.briar.api.ContactId;

/**
 * An event that is broadcast when a message is offered by a contact and needs
 * to be requested.
 */
public class MessageToRequestEvent extends DatabaseEvent {

	private final ContactId contactId;

	public MessageToRequestEvent(ContactId contactId) {
		this.contactId = contactId;
	}

	public ContactId getContactId() {
		return contactId;
	}
}
