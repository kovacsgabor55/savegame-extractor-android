package io.lerk.gtasase;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import io.lerk.gtasase.adapters.RemoteSavegameAdapter;
import io.lerk.gtasase.adapters.SavegameFileAdapter;
import io.lerk.gtasase.tasks.FileSearchTask;
import io.lerk.gtasase.tasks.SavegamesFetchTask;

public class SavegameActivity extends AppCompatActivity {

    public static final String PROTO_VERSION = "1";

    public static final String SERVICE_ADDRESS_KEY = "serviceAddress";
    public static final String SERVICE_PORT_KEY = "servicePort";
    private static final String TAG = SavegameActivity.class.getCanonicalName();
    private static final int SELECT_SAVE_REQUEST_CODE_FILE = 42;
    private static final int SELECT_SAVE_REQUEST_CODE_DIRECTORY = 43;
    private static final ArrayList<String> possibleSaves = new ArrayList<>(Arrays.asList(
            "GTASAsf1.b", "GTASAsf2.b", "GTASAsf3.b", "GTASAsf4.b", "GTASAsf5.b", "GTASAsf6.b", "GTASAsf7.b", "GTASAsf8.b"));

    private boolean localView = true;
    private ListView savegameListView;
    private ArrayAdapter<Pair<Uri, File>> localFileAdapter;
    private String serviceAddress;
    private Menu menu;

    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private SwipeRefreshLayout refreshLayout;
    private int servicePort;
    private RemoteSavegameAdapter remoteSavegameAdapter;
    private TextView noSavegamesFoundTextView;
    private boolean dialogShown = false;
    private Uri lastUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_savegame);

        refreshLayout = findViewById(R.id.savegameRefreshLayout);
        refreshLayout.setEnabled(false);
        refreshLayout.setColorSchemeResources(R.color.primaryLightColor, R.color.primaryColor, R.color.primaryDarkColor);

        noSavegamesFoundTextView = findViewById(R.id.noSavegamesFoundText);

        Intent intent = getIntent();

        ArrayList<String> arrayList = intent.getStringArrayListExtra(SERVICE_ADDRESS_KEY);
        if (arrayList == null) {
            Toast.makeText(this, R.string.invalid_service, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        String[] serviceAddresses = new String[arrayList.size()];
        for (int i = 0; i < arrayList.size(); i++) {
            serviceAddresses[i] = arrayList.get(i);
        }

        if (serviceAddresses.length <= 0) {
            throw new IllegalStateException("No addresses for service");
        }
        servicePort = intent.getIntExtra(SERVICE_PORT_KEY, 0);
        serviceAddress = serviceAddresses[0] + ":" + servicePort;

        savegameListView = findViewById(R.id.savegameListView);
        Log.i(TAG, "Using address: '" + serviceAddress + "'");

        MainActivity.verifyStoragePermissions(this);

        onLocalViewUpdated();

        final boolean permissionGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        if (!permissionGranted) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permissions_needed)
                    .setMessage(R.string.permissions_needed_message)
                    .setNeutralButton(R.string.okay, (dialog, which) ->
                            ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE))
                    .create().show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == SELECT_SAVE_REQUEST_CODE_FILE) {
                resolveSingleFile(data);
                refreshLayout.setRefreshing(false);
            } else if (requestCode == SELECT_SAVE_REQUEST_CODE_DIRECTORY) {
                resolveDirectory(data);
                refreshLayout.setRefreshing(false);
            }
        }
    }

    private void resolveDirectory(@Nullable Intent data) {
        if (data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                resolveDirectoryByUri(uri);
            }
        }
    }

    private void resolveDirectoryByUri(Uri uri) {
        ArrayList<Pair<Uri, File>> files = new ArrayList<>();
        if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile savegameFolder = DocumentFile.fromTreeUri(this, uri);
            if (savegameFolder != null) {
                lastUri = uri;
                DocumentFile[] savegameFiles = savegameFolder.listFiles();
                for (DocumentFile file : savegameFiles) {
                    resolveFilebyUri(files, file.getUri());
                }
            }
        } else {
            resolveSingleFileByUri(uri);
        }

        files.forEach(p -> localFileAdapter.add(p));
        //noinspection unchecked
        ((ArrayAdapter<Pair<Uri, File>>) savegameListView.getAdapter()).notifyDataSetChanged();
    }

    private void resolveSingleFile(@Nullable Intent data) {
        if (data != null) {
            Uri uri = data.getData();
            lastUri = uri;
            resolveSingleFileByUri(uri);
        }
    }

    private void resolveSingleFileByUri(Uri uri) {
        ArrayList<Pair<Uri, File>> files = new ArrayList<>();
        resolveFilebyUri(files, uri);
        files.forEach(p -> localFileAdapter.add(p));
        //noinspection unchecked
        ((ArrayAdapter<Pair<Uri, File>>) savegameListView.getAdapter()).notifyDataSetChanged();
    }

    private void resolveFilebyUri(ArrayList<Pair<Uri, File>> files, Uri uri) {
        if (uri != null) {
            if (uri.getPath() != null) {
                String pathname = null;
                if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    pathname = resolveGarbageUri(uri);
                }
                if (pathname == null) {
                    pathname = uri.getPath();
                }
                File savegame = new File(pathname);
                boolean hasValidName = possibleSaves.contains(savegame.getName());
                if (hasValidName) {
                    files.add(new Pair<>(uri, savegame));
                }
            }
        }
    }

    @Nullable
    private String resolveGarbageUri(Uri uri) {
        final String docId = DocumentsContract.getDocumentId(uri);
        final String[] split = docId.split(":");
        final String type = split[0];

        if ("primary".equalsIgnoreCase(type)) {
            //noinspection deprecation
            return Environment.getExternalStorageDirectory() + "/" + split[1];
        }
        return null;
    }

    /**
     * This method tries to find the primary storage path by using this app as a reference.
     *
     * @return a path similar to <pre>/storage/emulated/0</pre> or null if nothing was found.
     */
    @Nullable
    private String getStoragePath() {
        File externalFilesDir = getExternalFilesDir(null);
        if (externalFilesDir == null) {
            externalFilesDir = getFilesDir();
        }
        String[] split = externalFilesDir.getAbsolutePath().split(File.separator);
        for (int i = 0; i < split.length; i++) {
            if (split[i].equals("data")) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < i; j++) {
                    sb.append(split[j]).append(File.separator);
                }
                return sb.toString();
            }
        }
        return null;
    }

    private void runFileSearch(String searchDirectory, boolean useFileApi) {
        File directory = new File(searchDirectory);
        if (!directory.exists()) {
            Log.w(TAG, "Directory does not exist: '" + directory.getAbsolutePath() + "'");
        }
        refreshLayout.setRefreshing(true);
        FileSearchTask fileSearchTask = new FileSearchTask(directory, useFileApi, files -> {
            //noinspection unchecked
            files.forEach(f -> ((ArrayAdapter<Pair<Uri, File>>) savegameListView.getAdapter()).add(f));
            //noinspection unchecked
            ((ArrayAdapter<Pair<Uri, File>>) savegameListView.getAdapter()).notifyDataSetChanged();
            refreshLayout.setRefreshing(false);
        });
        fileSearchTask.execute();
    }

    public RemoteSavegameAdapter getRemoteSavegameAdapter() {
        return remoteSavegameAdapter;
    }

    private void onLocalViewUpdated() {
        updateToolbar();
        noSavegamesFoundTextView.setVisibility(View.VISIBLE);
        savegameListView.setVisibility(View.INVISIBLE);
        if (!localView) {
            remoteSavegameAdapter = new RemoteSavegameAdapter(this, R.layout.layout_savegame_item_remote, serviceAddress);
            remoteSavegameAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    super.onChanged();
                    if (remoteSavegameAdapter.getCount() <= 0) {
                        noSavegamesFoundTextView.setVisibility(View.VISIBLE);
                        savegameListView.setVisibility(View.INVISIBLE);
                    } else {
                        noSavegamesFoundTextView.setVisibility(View.GONE);
                        savegameListView.setVisibility(View.VISIBLE);
                    }
                }
            });
            savegameListView.setAdapter(remoteSavegameAdapter);
            new SavegamesFetchTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            localFileAdapter = new SavegameFileAdapter(this, R.layout.layout_savegame_item, serviceAddress, servicePort);
            localFileAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    super.onChanged();
                    if (localFileAdapter.getCount() <= 0) {
                        noSavegamesFoundTextView.setVisibility(View.VISIBLE);
                        savegameListView.setVisibility(View.INVISIBLE);
                    } else {
                        noSavegamesFoundTextView.setVisibility(View.GONE);
                        savegameListView.setVisibility(View.VISIBLE);
                    }
                }
            });
            savegameListView.setAdapter(localFileAdapter);
            startLocalFileSearch();
        }
    }

    private void startLocalFileSearch() {
        String searchMethod = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("searchMethod", getString(R.string.search_method_manual_file));
        if (searchMethod.equals(getString(R.string.search_method_manual_file))) {
            if (!dialogShown) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.file_selection_hint_file)
                        .setTitle(R.string.file_selection_hint_title)
                        .setPositiveButton(R.string.okay, (d, w) -> startFileChooserSingle()).show();
                dialogShown = true;
            } else if (lastUri != null) {
                resolveSingleFileByUri(lastUri);
            } else {
                startFileChooserSingle();
            }
        } else if (searchMethod.equals(getString(R.string.search_method_manual_directory))) {
            if (!dialogShown) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.file_selection_hint_directory)
                        .setTitle(R.string.file_selection_hint_title)
                        .setPositiveButton(R.string.okay, (d, w) -> startFileChooserTree()).show();
                dialogShown = true;
            } else if (lastUri != null) {
                resolveDirectoryByUri(lastUri);
            } else {
                startFileChooserTree();
            }
        } else if (searchMethod.equals(getString(R.string.search_method_command_line))) {
            runFileSearch(getStoragePath(), false);
        } else if (searchMethod.equals(getString(R.string.search_method_file_api))) {
            runFileSearch(getStoragePath(), true);
        }
    }

    /**
     * Opens a file chooser that can open a directory.
     */
    private void startFileChooserTree() {
        Intent searchIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(searchIntent, SELECT_SAVE_REQUEST_CODE_DIRECTORY);
    }

    /**
     * Opens a file chooser that can select only single files.
     */
    private void startFileChooserSingle() {
        Intent searchIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        searchIntent.addCategory(Intent.CATEGORY_OPENABLE);
        searchIntent.setType("*/*");
        startActivityForResult(searchIntent, SELECT_SAVE_REQUEST_CODE_FILE);
    }

    private void updateToolbar() {
        String titleText = serviceAddress + ((localView) ?
                getString(R.string.view_mode_suffix_local) : getString(R.string.view_mode_suffix_remote));
        ActionBar toolbar = getSupportActionBar();
        if (toolbar != null) {
            toolbar.setTitle(titleText);
        }
        if (menu != null) {
            MenuItem item = menu.findItem(R.id.menu_main_setting);
            if (item != null) {
                item.setIcon(((localView) ? R.drawable.ic_cloud_upload_white_24dp : R.drawable.ic_cloud_download_white_24dp));
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        localView = !localView;
        onLocalViewUpdated();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_service, menu);
        this.menu = menu;
        return true;
    }

    public String getServiceAddress() {
        return serviceAddress;
    }

    public void setLoading(boolean loading) {
        if (refreshLayout != null) {
            refreshLayout.setRefreshing(loading);
        }
    }
}
