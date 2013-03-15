package net.sf.briar.android.messages;

import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.widget.LinearLayout.VERTICAL;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import net.sf.briar.R;
import net.sf.briar.android.AscendingHeaderComparator;
import net.sf.briar.android.BriarActivity;
import net.sf.briar.android.BriarService;
import net.sf.briar.android.BriarService.BriarServiceConnection;
import net.sf.briar.android.widgets.CommonLayoutParams;
import net.sf.briar.android.widgets.HorizontalBorder;
import net.sf.briar.api.ContactId;
import net.sf.briar.api.db.DatabaseComponent;
import net.sf.briar.api.db.DatabaseExecutor;
import net.sf.briar.api.db.DbException;
import net.sf.briar.api.db.NoSuchContactException;
import net.sf.briar.api.db.PrivateMessageHeader;
import net.sf.briar.api.db.event.ContactRemovedEvent;
import net.sf.briar.api.db.event.DatabaseEvent;
import net.sf.briar.api.db.event.DatabaseListener;
import net.sf.briar.api.db.event.MessageExpiredEvent;
import net.sf.briar.api.db.event.PrivateMessageAddedEvent;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.google.inject.Inject;

public class ConversationActivity extends BriarActivity
implements DatabaseListener, OnClickListener, OnItemClickListener {

	private static final Logger LOG =
			Logger.getLogger(ConversationActivity.class.getName());

	private final BriarServiceConnection serviceConnection =
			new BriarServiceConnection();

	private String contactName = null;
	private ConversationAdapter adapter = null;
	private ListView list = null;

	// Fields that are accessed from DB threads must be volatile
	@Inject private volatile DatabaseComponent db;
	@Inject @DatabaseExecutor private volatile Executor dbExecutor;
	private volatile ContactId contactId = null;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(null);

		Intent i = getIntent();
		int id = i.getIntExtra("net.sf.briar.CONTACT_ID", -1);
		if(id == -1) throw new IllegalStateException();
		contactId = new ContactId(id);
		contactName = i.getStringExtra("net.sf.briar.CONTACT_NAME");
		if(contactName == null) throw new IllegalStateException();
		setTitle(contactName);

		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(CommonLayoutParams.MATCH_MATCH);
		layout.setOrientation(VERTICAL);
		layout.setGravity(CENTER_HORIZONTAL);

		adapter = new ConversationAdapter(this);
		list = new ListView(this);
		// Give me all the width and all the unused height
		list.setLayoutParams(CommonLayoutParams.MATCH_WRAP_1);
		list.setAdapter(adapter);
		list.setOnItemClickListener(this);
		layout.addView(list);

		layout.addView(new HorizontalBorder(this));

		ImageButton composeButton = new ImageButton(this);
		composeButton.setBackgroundResource(0);
		composeButton.setImageResource(R.drawable.content_new_email);
		composeButton.setOnClickListener(this);
		layout.addView(composeButton);

		setContentView(layout);

		// Bind to the service so we can wait for the DB to be opened
		bindService(new Intent(BriarService.class.getName()),
				serviceConnection, 0);
	}

	@Override
	public void onResume() {
		super.onResume();
		db.addListener(this);
		loadHeaders();
	}

	private void loadHeaders() {
		dbExecutor.execute(new Runnable() {
			public void run() {
				try {
					// Wait for the service to be bound and started
					serviceConnection.waitForStartup();
					// Load the headers from the database
					Collection<PrivateMessageHeader> headers =
							db.getPrivateMessageHeaders(contactId);
					// Display the headers in the UI
					displayHeaders(headers);
				} catch(NoSuchContactException e) {
					if(LOG.isLoggable(INFO)) LOG.info("Contact removed");
					finishOnUiThread();
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while waiting for service");
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private void displayHeaders(
			final Collection<PrivateMessageHeader> headers) {
		runOnUiThread(new Runnable() {
			public void run() {
				adapter.clear();
				for(PrivateMessageHeader h : headers) adapter.add(h);
				adapter.sort(AscendingHeaderComparator.INSTANCE);
				selectFirstUnread();
			}
		});
	}

	private void selectFirstUnread() {
		int firstUnread = -1, count = adapter.getCount();
		for(int i = 0; i < count; i++) {
			if(!adapter.getItem(i).isRead()) {
				firstUnread = i;
				break;
			}
		}
		if(firstUnread == -1) list.setSelection(count - 1);
		else list.setSelection(firstUnread);
	}

	@Override
	public void onActivityResult(int request, int result, Intent data) {
		if(result == ReadPrivateMessageActivity.RESULT_PREV) {
			int position = request - 1;
			if(position >= 0 && position < adapter.getCount())
				showMessage(position);
		} else if(result == ReadPrivateMessageActivity.RESULT_NEXT) {
			int position = request + 1;
			if(position >= 0 && position < adapter.getCount())
				showMessage(position);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		db.removeListener(this);
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		unbindService(serviceConnection);
	}

	// FIXME: Load operations may overlap, resulting in an inconsistent view
	public void eventOccurred(DatabaseEvent e) {
		if(e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if(c.getContactId().equals(contactId)) {
				if(LOG.isLoggable(INFO)) LOG.info("Contact removed");
				finishOnUiThread();
			}
		} else if(e instanceof MessageExpiredEvent) {
			if(LOG.isLoggable(INFO)) LOG.info("Message expired, reloading");
			loadHeaders(); // FIXME: Don't reload everything
		} else if(e instanceof PrivateMessageAddedEvent) {
			PrivateMessageAddedEvent p = (PrivateMessageAddedEvent) e;
			if(p.getContactId().equals(contactId)) {
				if(LOG.isLoggable(INFO)) LOG.info("Message added, reloading");
				loadHeaders();
			}
		}
	}

	public void onClick(View view) {
		Intent i = new Intent(this, WritePrivateMessageActivity.class);
		i.putExtra("net.sf.briar.CONTACT_ID", contactId.getInt());
		startActivity(i);
	}

	public void onItemClick(AdapterView<?> parent, View view, int position,
			long id) {
		showMessage(position);
	}

	private void showMessage(int position) {
		PrivateMessageHeader item = adapter.getItem(position);
		Intent i = new Intent(this, ReadPrivateMessageActivity.class);
		i.putExtra("net.sf.briar.CONTACT_ID", contactId.getInt());
		i.putExtra("net.sf.briar.CONTACT_NAME", contactName);
		i.putExtra("net.sf.briar.MESSAGE_ID", item.getId().getBytes());
		i.putExtra("net.sf.briar.CONTENT_TYPE", item.getContentType());
		i.putExtra("net.sf.briar.TIMESTAMP", item.getTimestamp());
		i.putExtra("net.sf.briar.INCOMING", item.isIncoming());
		i.putExtra("net.sf.briar.FIRST", position == 0);
		i.putExtra("net.sf.briar.LAST", position == adapter.getCount() - 1);
		startActivityForResult(i, position);
	}
}