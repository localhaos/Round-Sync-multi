package ca.pkay.rcloneexplorer.Settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import ca.pkay.rcloneexplorer.BuildConfig;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfigHelper;
import ca.pkay.rcloneexplorer.Services.RcdService;
import ca.pkay.rcloneexplorer.VirtualContentProvider;
import ca.pkay.rcloneexplorer.shizuku.ShizukuProcessManager;
import es.dmoral.toasty.Toasty;
import io.github.x0b.safdav.file.SafConstants;
import rikka.shizuku.Shizuku;

import java.util.List;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartActivityForResult;

public class FileAccessSettingsFragment extends Fragment {

    public static final int onDocumentTreeOpened = 1001;
    private static final int onAllFilesSettingOpened = 1002;
    private static final String ANDROID_AUTHORITY = "com.android.externalstorage.documents";

    private Context context;
    private ViewGroup fileAccessAll;
    private View shizukuEnabledContainer;
    private Switch shizukuEnabledSwitch;
    private View shizukuRootContainer;
    private Switch shizukuRootSwitch;
    private TextView shizukuStatus;
    private Button shizukuPermissionButton;
    private View safEnabledView;
    private Switch safEnabledSwitch;
    private View vcpEnabledContainer;
    private Switch vcpEnabledSwitch;
    private View vcpDeclareLocalContainer;
    private Switch vcpDeclareLocalSwitch;
    private PermissionListAdapter permissionList;
    private Button addPermissionBtn;
    private RecyclerView listView;
    private View addButtonContainer;
    private View refreshLaContainer;
    private Switch refreshLaSwitch;
    private View openAllFilesPerm;
    private View vcpGrantAllContainer;
    private Switch vcpGrantAllSwitch;
    private Rclone rclone;

    private final Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener =
            this::updateShizukuState;
    private final Shizuku.OnBinderDeadListener shizukuBinderDeadListener =
            this::updateShizukuState;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode == ShizukuProcessManager.PERMISSION_REQUEST_CODE) {
                    updateShizukuState();
                }
            };

    public static FileAccessSettingsFragment newInstance() {
        return new FileAccessSettingsFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.settings_fragment_file_access_settings, container, false);

        permissionList = new PermissionListAdapter(context.getContentResolver().getPersistedUriPermissions());
        listView = view.findViewById(R.id.file_permission_list);
        listView.setAdapter(permissionList);
        addButtonContainer = view.findViewById(R.id.permission_add_container);

        getViews(view);
        setDefaultStates();
        setClickListeners();

        if (getActivity() != null) {
            getActivity().setTitle(getString(R.string.pref_header_file_access));
        }

        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;
        rclone = new Rclone(context);
        ShizukuProcessManager.initialize(context);
    }

    @Override
    public void onStart() {
        super.onStart();
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener);
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener);
        updateShizukuState();
    }

    @Override
    public void onStop() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener);
        super.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.context = null;
    }

    private void getViews(View view) {
        shizukuEnabledContainer = view.findViewById(R.id.enable_shizuku_container);
        shizukuEnabledSwitch = view.findViewById(R.id.enable_shizuku_switch);
        shizukuRootContainer = view.findViewById(R.id.enable_shizuku_root_container);
        shizukuRootSwitch = view.findViewById(R.id.enable_shizuku_root_switch);
        shizukuStatus = view.findViewById(R.id.shizuku_status);
        shizukuPermissionButton = view.findViewById(R.id.shizuku_permission_button);
        safEnabledView = view.findViewById(R.id.enable_saf_view);
        safEnabledSwitch = view.findViewById(R.id.enable_saf_switch);
        fileAccessAll = view.findViewById(R.id.file_access_settings_all);
        addPermissionBtn = view.findViewById(R.id.permission_add_button);
        refreshLaContainer = view.findViewById(R.id.enable_refresh_la_container);
        refreshLaSwitch = view.findViewById(R.id.enable_refresh_la_switch);
        openAllFilesPerm = view.findViewById(R.id.open_all_files_setting_container);
        vcpEnabledContainer = view.findViewById(R.id.enable_saf_vcp_view);
        vcpEnabledSwitch = view.findViewById(R.id.enable_saf_vcp_switch);
        vcpDeclareLocalContainer = view.findViewById(R.id.vcp_declare_local_container);
        vcpDeclareLocalSwitch = view.findViewById(R.id.vcp_declare_local_switch);
        vcpGrantAllContainer = view.findViewById(R.id.vcp_grant_all_container);
        vcpGrantAllSwitch = view.findViewById(R.id.vcp_grant_all_switch);
    }

    private void setDefaultStates() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean shizukuEnabled = sharedPreferences.getBoolean(
                getString(R.string.pref_key_shizuku_enabled), false);
        shizukuEnabledSwitch.setChecked(shizukuEnabled);
        shizukuRootSwitch.setChecked(sharedPreferences.getBoolean(
                getString(R.string.pref_key_shizuku_root), false));
        setShizukuRootControlsEnabled(shizukuEnabled);
        boolean safEnabled = sharedPreferences.getBoolean(getString(R.string.pref_key_enable_saf), false);
        safEnabledSwitch.setChecked(safEnabled);
        boolean refreshLaEnabled = sharedPreferences.getBoolean(getString(R.string.pref_key_refresh_local_aliases), true);
        refreshLaSwitch.setChecked(refreshLaEnabled);
        if(!safEnabled) {
            listView.setVisibility(View.GONE);
            addButtonContainer.setVisibility(View.GONE);
        }
        boolean vcpEnabled = sharedPreferences.getBoolean(getString(R.string.pref_key_enable_vcp), false);
        vcpEnabledSwitch.setChecked(vcpEnabled);
        boolean vcpDeclareLocal = sharedPreferences.getBoolean(getString(R.string.pref_key_vcp_declare_local), true);
        vcpDeclareLocalSwitch.setChecked(vcpDeclareLocal);
        boolean vcpGrantAll = sharedPreferences.getBoolean(getString(R.string.pref_key_vcp_grant_all), false);
        vcpGrantAllSwitch.setChecked(vcpGrantAll);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            openAllFilesPerm.setVisibility(View.GONE);
        }
    }

    private void setClickListeners() {
        shizukuEnabledContainer.setOnClickListener(
                v -> shizukuEnabledSwitch.setChecked(!shizukuEnabledSwitch.isChecked()));
        shizukuEnabledSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> setShizukuEnabled(isChecked));
        shizukuRootContainer.setOnClickListener(v -> {
            if (shizukuRootSwitch.isEnabled()) {
                shizukuRootSwitch.setChecked(!shizukuRootSwitch.isChecked());
            }
        });
        shizukuRootSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> setShizukuRootRequired(isChecked));
        shizukuPermissionButton.setOnClickListener(v -> requestShizukuPermission());
        safEnabledView.setOnClickListener(v -> safEnabledSwitch.setChecked(!safEnabledSwitch.isChecked()));
        safEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setSafEnabled(isChecked));
        addPermissionBtn.setOnClickListener(v -> addRoot());
        refreshLaContainer.setOnClickListener(v -> refreshLaSwitch.setChecked(!refreshLaSwitch.isChecked()));
        refreshLaSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setRefreshLa(isChecked));
        openAllFilesPerm.setOnClickListener(v -> openAndroidRAllFilesSettings());
        vcpEnabledContainer.setOnClickListener(v -> vcpEnabledSwitch.setChecked(!vcpEnabledSwitch.isChecked()));
        vcpEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setVcpEnabled(isChecked));
        vcpDeclareLocalContainer.setOnClickListener(v -> vcpDeclareLocalSwitch.setChecked(!vcpDeclareLocalSwitch.isChecked()));
        vcpDeclareLocalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setDeclareLocalEnabled(isChecked));
        vcpGrantAllContainer.setOnClickListener(v -> vcpGrantAllSwitch.setChecked(!vcpGrantAllSwitch.isChecked()));
        vcpGrantAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setGrantAllEnabled(isChecked));
    }

    private void setShizukuEnabled(boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(getString(R.string.pref_key_shizuku_enabled), enabled)
                .apply();
        setShizukuRootControlsEnabled(enabled);
        if (enabled) {
            ShizukuProcessManager.bindIfPossible();
            if (ShizukuProcessManager.getState(context)
                    == ShizukuProcessManager.State.PERMISSION_REQUIRED) {
                requestShizukuPermission();
            }
        } else {
            ShizukuProcessManager.unbind();
        }
        updateShizukuState();
    }

    private void setShizukuRootRequired(boolean required) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(getString(R.string.pref_key_shizuku_root), required)
                .apply();
        updateShizukuState();
    }

    private void setShizukuRootControlsEnabled(boolean enabled) {
        shizukuRootContainer.setEnabled(enabled);
        shizukuRootSwitch.setEnabled(enabled);
        shizukuRootContainer.setAlpha(enabled ? 1.0f : 0.5f);
    }

    private void requestShizukuPermission() {
        if (!ShizukuProcessManager.requestPermission(context)) {
            Toast.makeText(context, R.string.pref_shizuku_permission_failed, Toast.LENGTH_LONG).show();
        }
        updateShizukuState();
    }

    private void updateShizukuState() {
        if (!isAdded() || context == null || shizukuStatus == null) {
            return;
        }
        ShizukuProcessManager.State state = ShizukuProcessManager.getState(context);
        boolean rootRequired = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(getString(R.string.pref_key_shizuku_root), false);
        int statusResource;
        switch (state) {
            case UNSUPPORTED:
                statusResource = R.string.pref_shizuku_status_unsupported;
                break;
            case PERMISSION_REQUIRED:
                statusResource = R.string.pref_shizuku_status_permission;
                break;
            case CONNECTING:
                statusResource = R.string.pref_shizuku_status_connecting;
                break;
            case SHELL:
                statusResource = rootRequired
                        ? R.string.pref_shizuku_status_root_required
                        : R.string.pref_shizuku_status_shell;
                break;
            case ROOT:
                statusResource = R.string.pref_shizuku_status_root;
                break;
            case UNAVAILABLE:
            default:
                statusResource = R.string.pref_shizuku_status_unavailable;
                break;
        }
        shizukuStatus.setText(statusResource);
        shizukuPermissionButton.setText(state == ShizukuProcessManager.State.PERMISSION_REQUIRED
                ? R.string.pref_shizuku_grant
                : R.string.pref_shizuku_retry);
    }

    private void setSafEnabled(boolean isChecked) {
        if (isChecked) {
            listView.setVisibility(View.VISIBLE);
            addButtonContainer.setVisibility(View.VISIBLE);
            createSafRemote();
        } else {
            listView.setVisibility(View.GONE);
            addButtonContainer.setVisibility(View.GONE);
            rclone.deleteRemote(SafConstants.SAF_REMOTE_NAME);
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(getString(R.string.pref_key_enable_saf), isChecked);
        editor.apply();
    }

    private void setRefreshLa(boolean isChecked) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        pref.edit().putBoolean(getString(R.string.pref_key_refresh_local_aliases), isChecked)
                .remove(getString(R.string.pref_key_accessible_storage_locations)).apply();
    }

    private void openAndroidRAllFilesSettings() {
        @SuppressLint("InlinedApi")
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.fromParts("package", BuildConfig.APPLICATION_ID, null));
        tryStartActivityForResult(this, intent, onAllFilesSettingOpened);
    }

    private void setVcpEnabled(boolean isChecked) {
        if (!isChecked) {
            Intent stopIntent = new Intent(context, RcdService.class);
            stopIntent.setAction(RcdService.ACTION_STOP_FOREGROUND);
            context.startService(stopIntent);
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(getString(R.string.pref_key_enable_vcp), isChecked).apply();
        Uri rootUri = VirtualContentProvider.getRootUri();
        context.getContentResolver().notifyChange(rootUri, null);
    }

    private void setDeclareLocalEnabled(boolean isChecked) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(getString(R.string.pref_key_vcp_declare_local), isChecked).apply();
    }

    private void setGrantAllEnabled(boolean isChecked) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(getString(R.string.pref_key_vcp_grant_all), isChecked).apply();
    }

    private void createSafRemote() {
        RemoteConfigHelper.enableSaf(getContext());
    }

    public void addRoot() {
        Intent treeOpenIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        treeOpenIntent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        tryStartActivityForResult(this, treeOpenIntent, onDocumentTreeOpened);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case onDocumentTreeOpened:
                onTreeResult(resultCode, data);
                break;
            case onAllFilesSettingOpened:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    Toasty.success(context, "You can now access all local storage", Toast.LENGTH_LONG, true).show();
                } else {
                    Toasty.info(context, "Manage files not granted", Toast.LENGTH_LONG, true).show();
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @SuppressLint("WrongConstant") // i am not sure why the flags are not recognized, so we ignore it
    private void onTreeResult(int resultCode, Intent data) {
        if (Activity.RESULT_OK == resultCode) {
            Uri uri = data.getData();
            if (null == uri || uri.getAuthority().equals(BuildConfig.VCP_AUTHORITY)) {
                Toasty.error(context, getString(R.string.saf_uri_permission_error), Toast.LENGTH_LONG, true).show();
                return;
            }
            int takeFlags = data.getFlags();
            ContentResolver contentResolver = context.getContentResolver();
            for (UriPermission uriPermission : contentResolver.getPersistedUriPermissions()) {
                if (uri.toString().startsWith(uriPermission.getUri().toString())) {
                    // granted uri is less specific than already granted ones, reject
                    Toasty.error(context, getString(R.string.saf_uri_permission_already_added), Toast.LENGTH_LONG, true).show();
                    return;
                }
            }
            if(!ANDROID_AUTHORITY.equals(uri.getAuthority())){
                String msg = "Third party providers are not tested well, proceed with caution.";
                Toasty.warning(context, msg, Toast.LENGTH_LONG, true).show();
            }
            contentResolver.takePersistableUriPermission(
                    uri,
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION) & takeFlags
            );
            permissionList.updatePermissions(context.getContentResolver().getPersistedUriPermissions());
            Toasty.normal(context, getString(R.string.saf_uri_permission_added), Toast.LENGTH_SHORT).show();

        } else if (Activity.RESULT_CANCELED == resultCode) {
            Toasty.normal(context, getString(R.string.saf_uri_permission_cancelled), Toast.LENGTH_SHORT).show();
        }
    }

    private static class PermissionsViewHolder extends RecyclerView.ViewHolder {

        TextView text;
        UriPermission permission;

        public PermissionsViewHolder(@NonNull View itemView, final PermissionListAdapter adapter) {
            super(itemView);
            text = itemView.findViewById(R.id.permission_path);
            itemView.findViewById(R.id.permission_remove).setOnClickListener(v -> adapter.remove(getAdapterPosition()));
        }
    }

    private class PermissionListAdapter extends RecyclerView.Adapter<PermissionsViewHolder> {

        List<UriPermission> permissions;

        public PermissionListAdapter(List<UriPermission> permissions) {
            this.permissions = permissions;
        }

        @NonNull
        @Override
        public PermissionsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int i) {
            View v = LayoutInflater.from(context).inflate(R.layout.fragment_permission_item, parent, false);
            return new PermissionsViewHolder(v, this);
        }

        @Override
        public void onBindViewHolder(@NonNull PermissionsViewHolder holder, int position) {
            holder.permission = permissions.get(position);
            String authority = holder.permission.getUri().getAuthority();
            String permissionLabel = holder.permission.getUri().getPath();
            if (permissionLabel.startsWith("/tree/")) {
                permissionLabel = permissionLabel.substring(6);
            }
            if (!ANDROID_AUTHORITY.equals(authority)) {
                permissionLabel = authority + " (" + permissionLabel + ")";
            } else if ("primary:".equals(permissionLabel)) {
                permissionLabel = getString(R.string.pref_saf_permission_label_primary, permissionLabel);
            } else {
                permissionLabel = getString(R.string.pref_saf_permission_label_external, permissionLabel);
            }
            holder.text.setText(permissionLabel);
        }

        @Override
        public int getItemCount() {
            return permissions.size();
        }

        public void add(UriPermission permission) {
            permissions.add(permission);
            notifyDataSetChanged();
        }

        public void remove(int index) {
            UriPermission permission = permissions.get(index);
            context.getContentResolver().releasePersistableUriPermission(permission.getUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            permissions.remove(index);
            notifyDataSetChanged();
        }

        public void updatePermissions(List<UriPermission> newPermissions) {
            this.permissions = newPermissions;
            notifyDataSetChanged();
        }
    }
}
