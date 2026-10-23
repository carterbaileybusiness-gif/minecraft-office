package local.carter.office.client.helper;

import local.carter.office.client.helper.HelperProtocol.Message;

/** The mod's side of the link to the local helper app ({@link HelperConnection}; a fake in tests). */
public interface HelperChannel {
	/** Starts connecting (and reconnecting) in the background. */
	void start();

	/** Sends one message if connected (after WELCOME); false when not connected or the write failed. Any thread. */
	boolean send(Message message);

	/** Disconnects for good (the helper then quits and its virtual displays vanish). */
	void close();
}
