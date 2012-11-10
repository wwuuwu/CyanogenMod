/*
 * Copyright (C) 2012 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cyanogenmod.filemanager.ui.widgets;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.os.AsyncTask;
import android.os.storage.StorageVolume;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.cyanogenmod.filemanager.FileManagerApplication;
import com.cyanogenmod.filemanager.R;
import com.cyanogenmod.filemanager.adapters.FileSystemObjectAdapter;
import com.cyanogenmod.filemanager.adapters.FileSystemObjectAdapter.OnSelectionChangedListener;
import com.cyanogenmod.filemanager.console.ConsoleAllocException;
import com.cyanogenmod.filemanager.listeners.OnHistoryListener;
import com.cyanogenmod.filemanager.listeners.OnRequestRefreshListener;
import com.cyanogenmod.filemanager.listeners.OnSelectionListener;
import com.cyanogenmod.filemanager.model.Directory;
import com.cyanogenmod.filemanager.model.FileSystemObject;
import com.cyanogenmod.filemanager.model.ParentDirectory;
import com.cyanogenmod.filemanager.model.Symlink;
import com.cyanogenmod.filemanager.parcelables.NavigationViewInfoParcelable;
import com.cyanogenmod.filemanager.parcelables.SearchInfoParcelable;
import com.cyanogenmod.filemanager.preferences.AccessMode;
import com.cyanogenmod.filemanager.preferences.FileManagerSettings;
import com.cyanogenmod.filemanager.preferences.NavigationLayoutMode;
import com.cyanogenmod.filemanager.preferences.ObjectIdentifier;
import com.cyanogenmod.filemanager.preferences.Preferences;
import com.cyanogenmod.filemanager.ui.policy.DeleteActionPolicy;
import com.cyanogenmod.filemanager.ui.policy.IntentsActionPolicy;
import com.cyanogenmod.filemanager.ui.widgets.FlingerListView.OnItemFlingerListener;
import com.cyanogenmod.filemanager.ui.widgets.FlingerListView.OnItemFlingerResponder;
import com.cyanogenmod.filemanager.util.CommandHelper;
import com.cyanogenmod.filemanager.util.DialogHelper;
import com.cyanogenmod.filemanager.util.ExceptionUtil;
import com.cyanogenmod.filemanager.util.FileHelper;
import com.cyanogenmod.filemanager.util.MimeTypeHelper;
import com.cyanogenmod.filemanager.util.StorageHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The file manager implementation view (contains the graphical representation and the input
 * management for a file manager; shows the folders/files, the mode view, react touch events,
 * navigate, ...).
 */
public class NavigationView extends RelativeLayout implements
    AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener,
    BreadcrumbListener, OnSelectionChangedListener, OnSelectionListener, OnRequestRefreshListener {

    private static final String TAG = "NavigationView"; //$NON-NLS-1$

    /**
     * An interface to communicate selection changes events.
     */
    public interface OnNavigationSelectionChangedListener {
        /**
         * Method invoked when the selection changed.
         *
         * @param navView The navigation view that generate the event
         * @param selectedItems The new selected items
         */
        void onSelectionChanged(NavigationView navView, List<FileSystemObject> selectedItems);
    }

    /**
     * An interface to communicate a request for show the menu associated
     * with an item.
     */
    public interface OnNavigationRequestMenuListener {
        /**
         * Method invoked when a request to show the menu associated
         * with an item is started.
         *
         * @param navView The navigation view that generate the event
         * @param item The item for which the request was started
         */
        void onRequestMenu(NavigationView navView, FileSystemObject item);
    }

    /**
     * An interface to communicate a request when the user choose a file.
     */
    public interface OnFilePickedListener {
        /**
         * Method invoked when a request when the user choose a file.
         *
         * @param item The item choose
         */
        void onFilePicked(FileSystemObject item);
    }

    /**
     * The navigation view mode
     * @hide
     */
    public enum NAVIGATION_MODE {
        /**
         * The navigation view acts as a browser, and allow open files itself.
         */
        BROWSABLE,
        /**
         * The navigation view acts as a picker of files
         */
        PICKABLE,
    }

    /**
     * A listener for flinging events from {@link FlingerListView}
     */
    private final OnItemFlingerListener mOnItemFlingerListener = new OnItemFlingerListener() {

        @Override
        public boolean onItemFlingerStart(
                AdapterView<?> parent, View view, int position, long id) {
            try {
                // Response if the item can be removed
                FileSystemObjectAdapter adapter = (FileSystemObjectAdapter)parent.getAdapter();
                FileSystemObject fso = adapter.getItem(position);
                if (fso != null) {
                    if (fso instanceof ParentDirectory) {
                        return false;
                    }
                    return true;
                }
            } catch (Exception e) {
                ExceptionUtil.translateException(getContext(), e, true, false);
            }
            return false;
        }

        @Override
        public void onItemFlingerEnd(OnItemFlingerResponder responder,
                AdapterView<?> parent, View view, int position, long id) {

            try {
                // Response if the item can be removed
                FileSystemObjectAdapter adapter = (FileSystemObjectAdapter)parent.getAdapter();
                FileSystemObject fso = adapter.getItem(position);
                if (fso != null) {
                    DeleteActionPolicy.removeFileSystemObject(
                            getContext(),
                            fso,
                            NavigationView.this,
                            NavigationView.this,
                            responder);
                    return;
                }

                // Cancels the flinger operation
                responder.cancel();

            } catch (Exception e) {
                ExceptionUtil.translateException(getContext(), e, true, false);
                responder.cancel();
            }
        }
    };

    private int mId;
    private int mX;
    private int mY;
    private String mCurrentDir;
    private FileSystemObject mSelectedDir;
    private NavigationLayoutMode mCurrentMode;
    /**
     * @hide
     */
    List<FileSystemObject> mFiles;
    private FileSystemObjectAdapter mAdapter;

    private final Object mSync = new Object();

    private OnHistoryListener mOnHistoryListener;
    private OnNavigationSelectionChangedListener mOnNavigationSelectionChangedListener;
    private OnNavigationRequestMenuListener mOnNavigationRequestMenuListener;
    private OnFilePickedListener mOnFilePickedListener;

    private boolean mChRooted;

    private NAVIGATION_MODE mNavigationMode;

    private String mMimeType = MimeTypeHelper.ALL_MIME_TYPES;

    /**
     * @hide
     */
    Breadcrumb mBreadcrumb;
    /**
     * @hide
     */
    NavigationCustomTitleView mTitle;
    /**
     * @hide
     */
    AdapterView<?> mAdapterView;

    //The layout for icons mode
    private static final int RESOURCE_MODE_ICONS_LAYOUT = R.layout.navigation_view_icons;
    private static final int RESOURCE_MODE_ICONS_ITEM = R.layout.navigation_view_icons_item;
    //The layout for simple mode
    private static final int RESOURCE_MODE_SIMPLE_LAYOUT = R.layout.navigation_view_simple;
    private static final int RESOURCE_MODE_SIMPLE_ITEM = R.layout.navigation_view_simple_item;
    //The layout for details mode
    private static final int RESOURCE_MODE_DETAILS_LAYOUT = R.layout.navigation_view_details;
    private static final int RESOURCE_MODE_DETAILS_ITEM = R.layout.navigation_view_details_item;

    //The current layout identifier (is shared for all the mode layout)
    private static final int RESOURCE_CURRENT_LAYOUT = R.id.navigation_view_layout;

    /**
     * Constructor of <code>NavigationView</code>.
     *
     * @param context The current context
     * @param attrs The attributes of the XML tag that is inflating the view.
     */
    public NavigationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.Navigable);
        try {
            init(a);
        } finally {
            a.recycle();
        }
    }

    /**
     * Constructor of <code>NavigationView</code>.
     *
     * @param context The current context
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyle The default style to apply to this view. If 0, no style
     *        will be applied (beyond what is included in the theme). This may
     *        either be an attribute resource, whose value will be retrieved
     *        from the current theme, or an explicit style resource.
     */
    public NavigationView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.Navigable, defStyle, 0);
        try {
            init(a);
        } finally {
            a.recycle();
        }
    }

    /**
     * Invoked when the instance need to be saved.
     *
     * @return NavigationViewInfoParcelable The serialized info
     */
    public NavigationViewInfoParcelable onSaveState() {
        //Return the persistent the data
        NavigationViewInfoParcelable parcel = new NavigationViewInfoParcelable();
        parcel.setId(this.mId);
        parcel.setCurrentDir(this.mCurrentDir);
        parcel.setSelectedDir(this.mSelectedDir.getName());
        parcel.setSelParentDir(this.mSelectedDir.getParent());
        parcel.setChRooted(this.mChRooted);
        parcel.setSelectedFiles(this.mAdapter.getSelectedItems());
        parcel.setFiles(this.mFiles);
        parcel.setY(this.mY);
        parcel.setX(this.mX);
        //this.mX = this.mY = 0;
        return parcel;
    }

    /**
     * Invoked when the instance need to be restored.
     *
     * @param info The serialized info
     */
    public void onRestoreState(NavigationViewInfoParcelable info) {
        //Restore the data
        this.mId = info.getId();
        this.mCurrentDir = info.getCurrentDir();
        this.mChRooted = info.getChRooted();
        this.mFiles = info.getFiles();
        this.mAdapter.setSelectedItems(info.getSelectedFiles());
        this.mY = info.getY();
        this.mX = info.getX();
        String dirName = info.getSelectedDir();
        String parentName = info.getSelParentDir();
        
        FileSystemObject selectedObj = null;
        if(mSelectedDir != null){
        	selectedObj = new Directory(dirName, parentName, null, null, null, null);
        }
        //Update the views
        if(selectedObj == null){
        	refresh();
        }
        else{
        	refresh(selectedObj);
        }
        //this.mAdapterView.setSelection(this.mY);
    }

    /**
     * Method that initializes the view. This method loads all the necessary
     * information and create an appropriate layout for the view.
     *
     * @param tarray The type array
     */
    private void init(TypedArray tarray) {
        // Retrieve the mode
        this.mNavigationMode = NAVIGATION_MODE.BROWSABLE;
        int mode = tarray.getInteger(
                                R.styleable.Navigable_navigation,
                                NAVIGATION_MODE.BROWSABLE.ordinal());
        if (mode >= 0 && mode < NAVIGATION_MODE.values().length) {
            this.mNavigationMode = NAVIGATION_MODE.values()[mode];
        }

        //Initialize variables
        this.mFiles = new ArrayList<FileSystemObject>();

        // Is ChRooted environment?
        if (this.mNavigationMode.compareTo(NAVIGATION_MODE.PICKABLE) == 0) {
            // Pick mode is always ChRooted
            this.mChRooted = true;
        } else {
            this.mChRooted =
                    FileManagerApplication.getAccessMode().compareTo(AccessMode.SAFE) == 0;
        }

        //Retrieve the default configuration
        if (this.mNavigationMode.compareTo(NAVIGATION_MODE.BROWSABLE) == 0) {
            SharedPreferences preferences = Preferences.getSharedPreferences();
            int viewMode = preferences.getInt(
                    FileManagerSettings.SETTINGS_LAYOUT_MODE.getId(),
                    ((ObjectIdentifier)FileManagerSettings.
                            SETTINGS_LAYOUT_MODE.getDefaultValue()).getId());
            changeViewMode(NavigationLayoutMode.fromId(viewMode));
        } else {
            // Pick mode has always a details layout
            changeViewMode(NavigationLayoutMode.DETAILS);
        }
    }

    /**
     * Method that returns the mime/type used by this class. Only the files with this mime/type
     * are shown.
     *
     * @return String The mime/type
     */
    public String getMimeType() {
        return this.mMimeType;
    }

    /**
     * Method that sets the mime/type used by this class. Only the files with this mime/type
     * are shown.
     *
     * @param mimeType String The mime/type
     */
    public void setMimeType(String mimeType) {
        this.mMimeType = mimeType;
    }

    /**
     * Method that returns the current file list of the navigation view.
     *
     * @return List<FileSystemObject> The current file list of the navigation view
     */
    public List<FileSystemObject> getFiles() {
        if (this.mFiles == null) {
            return null;
        }
        return new ArrayList<FileSystemObject>(this.mFiles);
    }

    /**
     * Method that returns the current file list of the navigation view.
     *
     * @return List<FileSystemObject> The current file list of the navigation view
     */
    public List<FileSystemObject> getSelectedFiles() {
        if (this.mAdapter != null && this.mAdapter.getSelectedItems() != null) {
            return new ArrayList<FileSystemObject>(this.mAdapter.getSelectedItems());
        }
        return null;
    }

    /**
     * Method that returns the custom title fragment associated with this navigation view.
     *
     * @return NavigationCustomTitleView The custom title view fragment
     */
    public NavigationCustomTitleView getCustomTitle() {
        return this.mTitle;
    }

    /**
     * Method that associates the custom title fragment with this navigation view.
     *
     * @param title The custom title view fragment
     */
    public void setCustomTitle(NavigationCustomTitleView title) {
        this.mTitle = title;
    }

    /**
     * Method that returns the breadcrumb associated with this navigation view.
     *
     * @return Breadcrumb The breadcrumb view fragment
     */
    public Breadcrumb getBreadcrumb() {
        return this.mBreadcrumb;
    }

    /**
     * Method that associates the breadcrumb with this navigation view.
     *
     * @param breadcrumb The breadcrumb view fragment
     */
    public void setBreadcrumb(Breadcrumb breadcrumb) {
        this.mBreadcrumb = breadcrumb;
        this.mBreadcrumb.addBreadcrumbListener(this);
    }

    /**
     * Method that sets the listener for communicate history changes.
     *
     * @param onHistoryListener The listener for communicate history changes
     */
    public void setOnHistoryListener(OnHistoryListener onHistoryListener) {
        this.mOnHistoryListener = onHistoryListener;
    }

    /**
     * Method that sets the listener which communicates selection changes.
     *
     * @param onNavigationSelectionChangedListener The listener reference
     */
    public void setOnNavigationSelectionChangedListener(
            OnNavigationSelectionChangedListener onNavigationSelectionChangedListener) {
        this.mOnNavigationSelectionChangedListener = onNavigationSelectionChangedListener;
    }

    /**
     * Method that sets the listener for menu item requests.
     *
     * @param onNavigationRequestMenuListener The listener reference
     */
    public void setOnNavigationOnRequestMenuListener(
            OnNavigationRequestMenuListener onNavigationRequestMenuListener) {
        this.mOnNavigationRequestMenuListener = onNavigationRequestMenuListener;
    }

    /**
     * @return the mOnFilePickedListener
     */
    public OnFilePickedListener getOnFilePickedListener() {
        return this.mOnFilePickedListener;
    }

    /**
     * Method that sets the listener for picked items
     *
     * @param onFilePickedListener The listener reference
     */
    public void setOnFilePickedListener(OnFilePickedListener onFilePickedListener) {
        this.mOnFilePickedListener = onFilePickedListener;
    }

    /**
     * Method that sets if the view should use flinger gesture detection.
     *
     * @param useFlinger If the view should use flinger gesture detection
     */
    public void setUseFlinger(boolean useFlinger) {
        if (this.mCurrentMode.compareTo(NavigationLayoutMode.ICONS) == 0) {
            // Not supported
            return;
        }
        // Set the flinger listener (only when navigate)
        if (this.mNavigationMode.compareTo(NAVIGATION_MODE.BROWSABLE) == 0) {
            if (this.mAdapterView instanceof FlingerListView) {
                if (useFlinger) {
                    ((FlingerListView)this.mAdapterView).
                        setOnItemFlingerListener(this.mOnItemFlingerListener);
                } else {
                    ((FlingerListView)this.mAdapterView).setOnItemFlingerListener(null);
                }
            }
        }
    }

    /**
     * Method that forces the view to scroll to the file system object passed.
     *
     * @param fso The file system object
     */
    public void scrollTo(FileSystemObject fso) {
        if (fso != null) {
            try {
                int position = this.mAdapter.getPosition(fso);
                if(position == -1){
                	String tagPath = fso.getFullPath();
	                for(int i=0;i<this.mAdapter.getCount();i++){
	                	FileSystemObject temp = this.mAdapter.getItem(i);
	                	String tmpPath = temp.getFullPath();
	                	if(tmpPath.equals(tagPath) || tmpPath == tagPath){
	                		position = i;
	                		break;
	                	}
	                }
                }
                this.mAdapterView.setSelection(position);
            } catch (Exception e) {
                this.mAdapterView.setSelection(0);
            }
        } else {
            this.mAdapterView.setSelection(0);
        }
    }

    /**
     * Method that refresh the view data.
     */
    public void refresh() {
        FileSystemObject fso = null;
        // Try to restore the previous scroll position
        try {
            if (this.mAdapterView != null && this.mAdapter != null) {
                int position = this.mAdapterView.getFirstVisiblePosition();
                fso = this.mAdapter.getItem(position);
            }
        } catch (Throwable _throw) {/**NON BLOCK**/}
        refresh(fso);
    }

    /**
     * Method that refresh the view data.
     *
     * @param scrollTo Scroll to object
     */
    public void refresh(FileSystemObject scrollTo) {
        //Check that current directory was set
        if (this.mCurrentDir == null || this.mFiles == null) {
            return;
        }

        //Reload data
        changeCurrentDir(this.mCurrentDir, false, true, false, null, scrollTo);
    }

    /**
     * Method that change the view mode.
     *
     * @param newMode The new mode
     */
    @SuppressWarnings({ "unchecked", "null" })
    public void changeViewMode(final NavigationLayoutMode newMode) {
        synchronized (this.mSync) {
            //Check that it is really necessary change the mode
            if (this.mCurrentMode != null && this.mCurrentMode.compareTo(newMode) == 0) {
                return;
            }

            // If we should set the listview to response to flinger gesture detection
            boolean useFlinger =
                    Preferences.getSharedPreferences().getBoolean(
                            FileManagerSettings.SETTINGS_USE_FLINGER.getId(),
                                ((Boolean)FileManagerSettings.
                                        SETTINGS_USE_FLINGER.
                                            getDefaultValue()).booleanValue());

            //Creates the new layout
            AdapterView<ListAdapter> newView = null;
            int itemResourceId = -1;
            if (newMode.compareTo(NavigationLayoutMode.ICONS) == 0) {
                newView = (AdapterView<ListAdapter>)inflate(
                        getContext(), RESOURCE_MODE_ICONS_LAYOUT, null);
                itemResourceId = RESOURCE_MODE_ICONS_ITEM;

            } else if (newMode.compareTo(NavigationLayoutMode.SIMPLE) == 0) {
                newView =  (AdapterView<ListAdapter>)inflate(
                        getContext(), RESOURCE_MODE_SIMPLE_LAYOUT, null);
                itemResourceId = RESOURCE_MODE_SIMPLE_ITEM;

                // Set the flinger listener (only when navigate)
                if (this.mNavigationMode.compareTo(NAVIGATION_MODE.BROWSABLE) == 0) {
                    if (useFlinger && newView instanceof FlingerListView) {
                        ((FlingerListView)newView).
                            setOnItemFlingerListener(this.mOnItemFlingerListener);
                    }
                }

            } else if (newMode.compareTo(NavigationLayoutMode.DETAILS) == 0) {
                newView =  (AdapterView<ListAdapter>)inflate(
                        getContext(), RESOURCE_MODE_DETAILS_LAYOUT, null);
                itemResourceId = RESOURCE_MODE_DETAILS_ITEM;

                // Set the flinger listener (only when navigate)
                if (this.mNavigationMode.compareTo(NAVIGATION_MODE.BROWSABLE) == 0) {
                    if (useFlinger && newView instanceof FlingerListView) {
                        ((FlingerListView)newView).
                            setOnItemFlingerListener(this.mOnItemFlingerListener);
                    }
                }
            }

            //Get the current adapter and its adapter list
            List<FileSystemObject> files = new ArrayList<FileSystemObject>(this.mFiles);
            final AdapterView<ListAdapter> current =
                    (AdapterView<ListAdapter>)findViewById(RESOURCE_CURRENT_LAYOUT);
            FileSystemObjectAdapter adapter =
                    new FileSystemObjectAdapter(
                            getContext(),
                            new ArrayList<FileSystemObject>(),
                            itemResourceId,
                            this.mNavigationMode.compareTo(NAVIGATION_MODE.PICKABLE) == 0);
            adapter.setOnSelectionChangedListener(this);

            //Remove current layout
            if (current != null) {
                if (current.getAdapter() != null) {
                    //Save selected items before dispose adapter
                    FileSystemObjectAdapter currentAdapter =
                            ((FileSystemObjectAdapter)current.getAdapter());
                    adapter.setSelectedItems(currentAdapter.getSelectedItems());
                    currentAdapter.dispose();
                }
                removeView(current);
            }
            this.mFiles = files;
            adapter.addAll(files);
            adapter.notifyDataSetChanged();

            //Set the adapter
            this.mAdapter = adapter;
            newView.setAdapter(this.mAdapter);
            newView.setOnItemClickListener(NavigationView.this);

            //Add the new layout
            this.mAdapterView = newView;
            addView(newView, 0);
            this.mCurrentMode = newMode;

            // Pick mode doesn't implements the onlongclick
            if (this.mNavigationMode.compareTo(NAVIGATION_MODE.BROWSABLE) == 0) {
                this.mAdapterView.setOnItemLongClickListener(this);
            } else {
                this.mAdapterView.setOnItemLongClickListener(null);
            }

            //Save the preference (only in navigation browse mode)
            if (this.mNavigationMode.compareTo(NAVIGATION_MODE.BROWSABLE) == 0) {
                try {
                    Preferences.savePreference(
                            FileManagerSettings.SETTINGS_LAYOUT_MODE, newMode, true);
                } catch (Exception ex) {
                    Log.e(TAG, "Save of view mode preference fails", ex); //$NON-NLS-1$
                }
            }
        }
    }

    /**
     * Method that removes a {@link FileSystemObject} from the view
     *
     * @param fso The file system object
     */
    public void removeItem(FileSystemObject fso) {
        this.mAdapter.remove(fso);
        // Delete also from internal list
        if (fso != null) {
            int cc = this.mFiles.size()-1;
            for (int i = cc; i >= 0; i--) {
                FileSystemObject f = this.mFiles.get(i);
                if (f != null && f.compareTo(fso) == 0) {
                    this.mFiles.remove(i);
                    break;
                }
            }
        }
        this.mAdapter.notifyDataSetChanged();
    }

    /**
     * Method that removes a file system object from his path from the view
     *
     * @param path The file system object path
     */
    public void removeItem(String path) {
        FileSystemObject fso = this.mAdapter.getItem(path);
        if (fso != null) {
            this.mAdapter.remove(fso);
            this.mAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Method that returns the current directory.
     *
     * @return String The current directory
     */
    public String getCurrentDir() {
        return this.mCurrentDir;
    }

    /**
     * Method that changes the current directory of the view.
     *
     * @param newDir The new directory location
     */
    public void changeCurrentDir(final String newDir) {
        changeCurrentDir(newDir, true, false, false, null, null);
    }

    /**
     * Method that changes the current directory of the view.
     *
     * @param newDir The new directory location
     * @param searchInfo The search information (if calling activity is {@link "SearchActivity"})
     */
    public void changeCurrentDir(final String newDir, SearchInfoParcelable searchInfo) {
        changeCurrentDir(newDir, true, false, false, searchInfo, null);
    }

    /**
     * Method that changes the current directory of the view.
     *
     * @param newDir The new directory location
     * @param addToHistory Add the directory to history
     * @param reload Force the reload of the data
     * @param useCurrent If this method must use the actual data (for back actions)
     * @param searchInfo The search information (if calling activity is {@link "SearchActivity"})
     * @param scrollTo If not null, then listview must scroll to this item
     */
    private void changeCurrentDir(
            final String newDir, final boolean addToHistory,
            final boolean reload, final boolean useCurrent,
            final SearchInfoParcelable searchInfo, final FileSystemObject scrollTo) {

        // Check navigation security (don't allow to go outside the ChRooted environment if one
        // is created)
        final String fNewDir = checkChRootedNavigation(newDir);

        synchronized (this.mSync) {
            //Check that it is really necessary change the directory
            if (!reload && this.mCurrentDir != null && this.mCurrentDir.compareTo(fNewDir) == 0) {
                return;
            }

            final boolean hasChanged =
                    !(this.mCurrentDir != null && this.mCurrentDir.compareTo(fNewDir) == 0);
            final boolean isNewHistory = (this.mCurrentDir != null);

            //Execute the listing in a background process
            AsyncTask<String, Integer, List<FileSystemObject>> task =
                    new AsyncTask<String, Integer, List<FileSystemObject>>() {
                        /**
                         * {@inheritDoc}
                         */
                        @Override
                        protected List<FileSystemObject> doInBackground(String... params) {
                            try {
                                //Reset the custom title view and returns to breadcrumb
                                if (NavigationView.this.mTitle != null) {
                                    NavigationView.this.mTitle.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                NavigationView.this.mTitle.restoreView();
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    });
                                }


                                //Start of loading data
                                if (NavigationView.this.mBreadcrumb != null) {
                                    try {
                                        NavigationView.this.mBreadcrumb.startLoading();
                                    } catch (Throwable ex) {
                                        /**NON BLOCK**/
                                    }
                                }

                                //Get the files, resolve links and apply configuration
                                //(sort, hidden, ...)
                                List<FileSystemObject> files = NavigationView.this.mFiles;
                                if (!useCurrent) {
                                    files = CommandHelper.listFiles(getContext(), fNewDir, null);
                                }
                                return files;
                            } catch (final ConsoleAllocException e) {
                                //Show exception and exists
                                NavigationView.this.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Context ctx = getContext();
                                        Log.e(TAG, ctx.getString(
                                                R.string.msgs_cant_create_console), e);
                                        DialogHelper.showToast(ctx,
                                                R.string.msgs_cant_create_console,
                                                Toast.LENGTH_LONG);
                                        ((Activity)ctx).finish();
                                    }
                                });
                                return null;

                            } catch (Exception ex) {
                                //End of loading data
                                if (NavigationView.this.mBreadcrumb != null) {
                                    try {
                                        NavigationView.this.mBreadcrumb.endLoading();
                                    } catch (Throwable ex2) {
                                        /**NON BLOCK**/
                                    }
                                }

                                //Capture exception
                                ExceptionUtil.attachAsyncTask(
                                    ex,
                                    new AsyncTask<Object, Integer, Boolean>() {
                                        @Override
                                        @SuppressWarnings("unchecked")
                                        protected Boolean doInBackground(Object... taskParams) {
                                            final List<FileSystemObject> files =
                                                    (List<FileSystemObject>)taskParams[0];
                                            NavigationView.this.mAdapterView.post(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            onPostExecuteTask(
                                                                    files, addToHistory,
                                                                    isNewHistory, hasChanged,
                                                                    searchInfo, fNewDir, scrollTo);
                                                        }
                                                    });
                                            return Boolean.TRUE;
                                        }

                                    });
                                ExceptionUtil.translateException(getContext(), ex);
                            }
                            return null;
                        }

                        /**
                         * {@inheritDoc}
                         */
                        @Override
                        protected void onPostExecute(List<FileSystemObject> files) {
                            onPostExecuteTask(
                                    files, addToHistory, isNewHistory,
                                    hasChanged, searchInfo, fNewDir, scrollTo);
                        }
                   };
            task.execute(fNewDir);
        }
    }


    /**
     * Method invoked when a execution ends.
     *
     * @param files The files obtains from the list
     * @param addToHistory If add path to history
     * @param isNewHistory If is new history
     * @param hasChanged If current directory was changed
     * @param searchInfo The search information (if calling activity is {@link "SearchActivity"})
     * @param newDir The new directory
     * @param scrollTo If not null, then listview must scroll to this item
     * @hide
     */
    void onPostExecuteTask(
            List<FileSystemObject> files, boolean addToHistory, boolean isNewHistory,
            boolean hasChanged, SearchInfoParcelable searchInfo,
            String newDir, final FileSystemObject scrollTo) {
        try {
            //Check that there is not errors and have some data
            if (files == null) {
                return;
            }

            //Apply user preferences
            List<FileSystemObject> sortedFiles =
                    FileHelper.applyUserPreferences(files, this.mMimeType, this.mChRooted);

            //Remove parent directory if we are in the root of a chrooted environment
            if (this.mChRooted && StorageHelper.isStorageVolume(newDir)) {
                if (files.size() > 0 && files.get(0) instanceof ParentDirectory) {
                    files.remove(0);
                }
            }

            //Load the data
            loadData(sortedFiles);
            this.mFiles = sortedFiles;
            if (searchInfo != null) {
                searchInfo.setSuccessNavigation(true);
            }

            //Add to history?
            if (addToHistory && hasChanged && isNewHistory) {
                if (this.mOnHistoryListener != null) {
                    //Communicate the need of a history change
                    this.mOnHistoryListener.onNewHistory(onSaveState());
                }
            }
            //Change the breadcrumb。最上文件层次目录导航控件。
            if (this.mBreadcrumb != null) {
                this.mBreadcrumb.changeBreadcrumbPath(newDir, this.mChRooted);
            }

            //Scroll to object?
            
            if (scrollTo != null) {
                scrollTo(scrollTo);
            }
            
            //The current directory is now the "newDir"
            this.mCurrentDir = newDir;

        } finally {
            //If calling activity is search, then save the search history
            if (searchInfo != null) {
                this.mOnHistoryListener.onNewHistory(searchInfo);
            }

            //End of loading data
            try {
                NavigationView.this.mBreadcrumb.endLoading();
            } catch (Throwable ex) {
                /**NON BLOCK**/
            }
        }
    }

    /**
     * Method that loads the files in the adapter.
     *
     * @param files The files to load in the adapter
     * @hide
     */
    @SuppressWarnings("unchecked")
    private void loadData(final List<FileSystemObject> files) {
        //Notify data to adapter view
        final AdapterView<ListAdapter> view =
                (AdapterView<ListAdapter>)findViewById(RESOURCE_CURRENT_LAYOUT);
        FileSystemObjectAdapter adapter = (FileSystemObjectAdapter)view.getAdapter();
        adapter.clear();
        adapter.addAll(files);
        adapter.notifyDataSetChanged();
        view.setSelection(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        // Different actions depending on user preference

        // Get the adapter and the fso
        FileSystemObjectAdapter adapter = ((FileSystemObjectAdapter)parent.getAdapter());
        FileSystemObject fso = adapter.getItem(position);

        // Parent directory hasn't actions
        if (fso instanceof ParentDirectory) {
            return false;
        }

        // Pick mode doesn't implements the onlongclick
        if (this.mNavigationMode.compareTo(NAVIGATION_MODE.PICKABLE) == 0) {
            return false;
        }

        onRequestMenu(fso);
        return true; //Always consume the event
    }

    /**
     * Method that opens or navigates to the {@link FileSystemObject}
     *
     * @param fso The file system object
     */
    public void open(FileSystemObject fso) {
        open(fso, null);
    }

    /**
     * Method that opens or navigates to the {@link FileSystemObject}
     *
     * @param fso The file system object
     * @param searchInfo The search info
     */
    public void open(FileSystemObject fso, SearchInfoParcelable searchInfo) {
        // If is a folder, then navigate to
        if (FileHelper.isDirectory(fso)) {
            changeCurrentDir(fso.getFullPath(), searchInfo);
        } else {
            // Open the file with the preferred registered app
            IntentsActionPolicy.openFileSystemObject(getContext(), fso, false, null, null);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        try {
        	this.mSelectedDir = null;
            FileSystemObject fso = ((FileSystemObjectAdapter)parent.getAdapter()).getItem(position);
            if (fso instanceof ParentDirectory) {
                changeCurrentDir(fso.getParent(), true, false, false, null, null);
            } else if (fso instanceof Directory) {
            	this.mY = parent.getFirstVisiblePosition();
            	this.mSelectedDir = fso;
                changeCurrentDir(fso.getFullPath(), true, false, false, null, null);
            } else if (fso instanceof Symlink) {
                Symlink symlink = (Symlink)fso;
                if (symlink.getLinkRef() != null && symlink.getLinkRef() instanceof Directory) {
                    changeCurrentDir(
                            symlink.getLinkRef().getFullPath(), true, false, false, null, null);
                }
            } else {
                if (this.mNavigationMode.compareTo(NAVIGATION_MODE.BROWSABLE) == 0) {
                    // Open the file with the preferred registered app
                    IntentsActionPolicy.openFileSystemObject(getContext(), fso, false, null, null);
                } else {
                    // Request a file pick selection
                    if (this.mOnFilePickedListener != null) {
                        this.mOnFilePickedListener.onFilePicked(fso);
                    }
                }
            }
        } catch (Throwable ex) {
            ExceptionUtil.translateException(getContext(), ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRequestRefresh(Object o) {
        if (o instanceof FileSystemObject) {
            refresh((FileSystemObject)o);
        } else if (o == null) {
            refresh();
        }
        onDeselectAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRequestRemove(Object o) {
        if (o != null && o instanceof FileSystemObject) {
            removeItem((FileSystemObject)o);
        } else {
            onRequestRefresh(null);
        }
        onDeselectAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNavigateTo(Object o) {
        // Ignored
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onBreadcrumbItemClick(BreadcrumbItem item) {
        changeCurrentDir(item.getItemPath(), true, true, false, null, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSelectionChanged(final List<FileSystemObject> selectedItems) {
        if (this.mOnNavigationSelectionChangedListener != null) {
            this.mOnNavigationSelectionChangedListener.onSelectionChanged(this, selectedItems);
        }
    }

    /**
     * Method invoked when a request to show the menu associated
     * with an item is started.
     *
     * @param item The item for which the request was started
     */
    public void onRequestMenu(final FileSystemObject item) {
        if (this.mOnNavigationRequestMenuListener != null) {
            this.mOnNavigationRequestMenuListener.onRequestMenu(this, item);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onToggleSelection(FileSystemObject fso) {
        if (this.mAdapter != null) {
            this.mAdapter.toggleSelection(fso);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDeselectAll() {
        if (this.mAdapter != null) {
            this.mAdapter.deselectedAll();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSelectAllVisibleItems() {
        if (this.mAdapter != null) {
            this.mAdapter.selectedAllVisibleItems();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDeselectAllVisibleItems() {
        if (this.mAdapter != null) {
            this.mAdapter.deselectedAllVisibleItems();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<FileSystemObject> onRequestSelectedFiles() {
        return this.getSelectedFiles();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<FileSystemObject> onRequestCurrentItems() {
        return this.getFiles();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String onRequestCurrentDir() {
        return this.mCurrentDir;
    }

    /**
     * Method that creates a ChRooted environment, protecting the user to break anything
     * in the device
     * @hide
     */
    public void createChRooted() {
        // If we are in a ChRooted environment, then do nothing
        if (this.mChRooted) return;
        this.mChRooted = true;

        //Change to first storage volume
        StorageVolume[] volumes =
                StorageHelper.getStorageVolumes(getContext());
        if (volumes != null && volumes.length > 0) {
            changeCurrentDir(volumes[0].getPath(), false, true, false, null, null);
        }
    }

    /**
     * Method that exits from a ChRooted environment
     * @hide
     */
    public void exitChRooted() {
        // If we aren't in a ChRooted environment, then do nothing
        if (!this.mChRooted) return;
        this.mChRooted = false;

        // Refresh
        refresh();
    }

    /**
     * Method that ensures that the user don't go outside the ChRooted environment
     *
     * @param newDir The new directory to navigate to
     * @return String
     */
    private String checkChRootedNavigation(String newDir) {
        // If we aren't in ChRooted environment, then there is nothing to check
        if (!this.mChRooted) return newDir;

        // Check if the path is owned by one of the storage volumes
        if (!StorageHelper.isPathInStorageVolume(newDir)) {
            StorageVolume[] volumes = StorageHelper.getStorageVolumes(getContext());
            if (volumes != null && volumes.length > 0) {
                return volumes[0].getPath();
            }
        }
        return newDir;
    }

}