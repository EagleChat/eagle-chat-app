package eaglechat.eaglechat;

import android.app.LoaderManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import com.melnykov.fab.FloatingActionButton;


public class ContactsActivity extends CompatListActivity implements LoaderManager.LoaderCallbacks<Cursor> {

    FloatingActionButton mAddButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(this.getLocalClassName(), "onCreate called");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        mAddButton = (FloatingActionButton) findViewById(R.id.action_add_contact);
        mAddButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleLaunchAddContactsActivity();
            }
        });

        ListAdapter adapter = new MultiLayoutCursorAdapter(
                this,
                new ContactsDelegate(MessagesTable.COLUMN_CONTENT),
                android.R.layout.two_line_list_item,
                null,
                new String[]{ContactsTable.COLUMN_NAME, MessagesTable.COLUMN_CONTENT},
                new int[]{android.R.id.text1, android.R.id.text2},
                SimpleCursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER
        );

        setListAdapter(adapter);

        setListShown(false, false);

        setupListCAB();

        getLoaderManager().initLoader(0, null, this).forceLoad();
    }

    private void setupListCAB() {

        final ListView listView = getListView();

        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);

        listView.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
            @Override
            public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
            }

            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                Log.d(TAG, "onCreateActionMode called");

                MenuInflater inflater = mode.getMenuInflater();
                inflater.inflate(R.menu.menu_contacts_context, menu);

                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {

                switch(item.getItemId()) {
                    case R.id.delete:
                        long[] ids = listView.getCheckedItemIds();

                        deleteContacts(ids);

                        mode.finish();
                }

                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {

            }
        });

    }

    private void deleteContacts(long[] ids) {
        for (long id : ids) {
            Uri uri = ContentUris.withAppendedId(DatabaseProvider.CONTACTS_URI, id);
            getContentResolver().delete(uri, null, null);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_contacts, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        switch (id) {
            case R.id.action_burn:
                Util.burn(this);
                finish();
                return true;
            case R.id.action_my_details:
                MyDetailsActivity.launchMyDetailsActivity(this);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private boolean handleLaunchAddContactsActivity() {
        Intent activityIntent = new Intent(this, AddContactActivity.class);
        startActivity(activityIntent);
        return true;
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Log.d(getLocalClassName(), String.format("You selected id=%d", id));
        Intent intent = new Intent(this, ConversationActivity.class);
        intent.putExtra(ConversationActivity.CONTACT_ID, id);
        startActivity(intent);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        Log.d(getLocalClassName(), "onCreateLoader called");
        return new CursorLoader(this, DatabaseProvider.CONTACTS_WITH_LAST_MESSAGE_URI,
                new String[]{"contacts._id",
                        ContactsTable.COLUMN_NAME,
                        MessagesTable.COLUMN_CONTENT}, null, null,
                ContactsTable.COLUMN_NAME);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        Log.d(getLocalClassName(), "onLoadFinished called");
        if (data.getCount() > 0) {
            ((SimpleCursorAdapter) getListAdapter()).changeCursor(data);
        } else {
            ((SimpleCursorAdapter) getListAdapter()).changeCursor(null);
        }
        setListShown(true, false);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        Log.d(getLocalClassName(), "onLoaderReset called");
        ((SimpleCursorAdapter) getListAdapter()).changeCursor(null);
    }

    private class ContactsDelegate implements MultiLayoutCursorAdapter.Delegate {

        String mDecisionColumn;

        private ContactsDelegate(String decisionColumn) {
            mDecisionColumn = decisionColumn;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position, Cursor cursor) {
            cursor.moveToPosition(position);
            String content = cursor.getString(cursor.getColumnIndex(mDecisionColumn));

            int type = content == null ? 0 : 1;

            return type;
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent, LayoutInflater inflater) {
            int type = getItemViewType(cursor.getPosition(), cursor);
            View v;
            if (type == 0) {
                v = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                ((TextView)v.findViewById(android.R.id.text1))
                        .setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right_black_24dp, 0);
            } else {
                v = inflater.inflate(R.layout.list_contact_with_message, parent, false);
            }
            v.setBackgroundResource(R.drawable.list_item_activated);
            return v;
        }

    }
}
