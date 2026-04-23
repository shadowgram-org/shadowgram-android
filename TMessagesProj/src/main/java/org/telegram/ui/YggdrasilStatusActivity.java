package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import link.yggdrasil.yggstack.mobile.Yggstack;

public class YggdrasilStatusActivity extends BaseFragment {

    private static final int VIEW_TYPE_SHADOW = 0;
    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_TEXT_DETAIL = 2;
    private static final int VIEW_TYPE_TEXT_SETTING = 3;
    private static final int VIEW_TYPE_INFO = 4;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int statusHeaderRow;
    private int statusRow;
    private int addressRow;
    private int publicKeyRow;
    private int statusShadowRow;
    private int peersHeaderRow;
    private int peersStartRow;
    private int peersEndRow;
    private int noPeersRow;
    private int peersShadowRow;
    private int configHeaderRow;
    private int configStartRow;
    private int configEndRow;
    private int noConfigRow;
    private int configShadowRow;
    private int retryPeersRow;
    private int retryPeersShadowRow;
    private int pingedHeaderRow;
    private int pingedStartRow;
    private int pingedEndRow;
    private int noPingedRow;
    private int pingedShadowRow;

    private String statusString = "Unknown";
    private boolean statusRunning;
    private String addressString = "N/A";
    private String publicKeyString = "N/A";
    private final ArrayList<String> peersList = new ArrayList<>();
    private final ArrayList<String> configPeersList = new ArrayList<>();
    private final ArrayList<String> pingedPeersList = new ArrayList<>();
    private Runnable refreshRunnable;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Yggdrasil");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listAdapter = new ListAdapter(context);

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position == retryPeersRow) {
                retryPeers();
            } else if (position == statusRow || position == addressRow || position == publicKeyRow) {
                copyDetailValue(view);
            } else if (position >= peersStartRow && position < peersEndRow) {
                copyDetailValue(view);
            } else if (position >= configStartRow && position < configEndRow) {
                copyDetailValue(view);
            } else if (position >= pingedStartRow && position < pingedEndRow) {
                connectToPingedPeer(position - pingedStartRow);
            }
        });

        loadData();
        startRefresh();
        return fragmentView;
    }

    private void startRefresh() {
        refreshRunnable = () -> {
            loadData();
            AndroidUtilities.runOnUIThread(refreshRunnable, 500);
        };
        AndroidUtilities.runOnUIThread(refreshRunnable, 500);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (refreshRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
            refreshRunnable = null;
        }
    }

    private void copyDetailValue(View view) {
        if (view instanceof TextDetailSettingsCell) {
            CharSequence value = ((TextDetailSettingsCell) view).getValueTextView().getText();
            if (value != null && value.length() > 0) {
                AndroidUtilities.addToClipboard(value);
                BulletinFactory.of(this).createCopyBulletin("Copied to clipboard").show();
            }
        }
    }

    private void loadData() {
        Yggstack ygg = ApplicationLoader.yggInstance;
        if (ygg != null) {
            try {
                statusRunning = ygg.isRunning();
                statusString = statusRunning ? "Running" : "Stopped";
                addressString = ygg.getAddress();
                publicKeyString = ygg.getPublicKey();

                peersList.clear();
                String peersJson = ygg.getPeersJSON();
                if (peersJson != null && !peersJson.isEmpty()) {
                    JSONArray arr = new JSONArray(peersJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject peer = arr.getJSONObject(i);
                        StringBuilder sb = new StringBuilder();
                        sb.append(peer.optString("URI", "unknown"));
                        boolean up = peer.optBoolean("Up", false);
                        sb.append(up ? " [UP]" : " [DOWN]");
                        long uptimeNs = peer.optLong("Uptime", 0);
                        if (uptimeNs > 0) {
                            sb.append(" | up: ").append(formatUptime(uptimeNs / 1_000_000_000.0));
                        }
                        long latencyNs = peer.optLong("Latency", 0);
                        if (latencyNs > 0) {
                            sb.append(" | lat: ").append(String.format("%.1fms", latencyNs / 1_000_000.0));
                        }
                        long rx = peer.optLong("RXBytes", 0);
                        long tx = peer.optLong("TXBytes", 0);
                        if (rx > 0 || tx > 0) {
                            sb.append(" | rx: ").append(formatBytes(rx));
                            sb.append(" tx: ").append(formatBytes(tx));
                        }
                        int cost = peer.optInt("Cost", 0);
                        if (cost > 0) {
                            sb.append(" | cost: ").append(cost);
                        }
                        if (peer.has("LastError") && !peer.isNull("LastError")) {
                            JSONObject err = peer.optJSONObject("LastError");
                            if (err != null) {
                                sb.append("\nerr: ").append(err.optString("Op", "")).append(" ").append(err.optString("Net", ""));
                            }
                        }
                        peersList.add(sb.toString());
                    }
                }
            } catch (Exception e) {
                statusString = "Error: " + e.getMessage();
                statusRunning = false;
            }
        } else {
            if (ApplicationLoader.isPeersReiniting) {
                statusString = "Initializing...";
            } else {
                statusString = "Not started";
            }
            statusRunning = false;
            addressString = "N/A";
            publicKeyString = "N/A";
            peersList.clear();
        }

        configPeersList.clear();
        try {
            SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("yggstack_prefs", Context.MODE_PRIVATE);
            String configJson = prefs.getString("ygg_config", null);
            if (configJson != null) {
                JSONObject config = new JSONObject(configJson);
                if (config.has("Peers")) {
                    JSONArray peers = config.getJSONArray("Peers");
                    for (int i = 0; i < peers.length(); i++) {
                        configPeersList.add(peers.getString(i));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        pingedPeersList.clear();
        java.util.List<link.yggdrasil.yggstack.android.data.PublicPeerInfo> pinged = ApplicationLoader.lastPingedPeers;
        if (pinged != null) {
            for (link.yggdrasil.yggstack.android.data.PublicPeerInfo p : pinged) {
                String rttStr = p.getRtt() != null ? p.getRtt() + "ms" : "unreachable";
                pingedPeersList.add(p.getUri() + " (" + rttStr + ")");
            }
        }

        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void updateRows() {
        rowCount = 0;

        statusHeaderRow = rowCount++;
        statusRow = rowCount++;
        addressRow = rowCount++;
        publicKeyRow = rowCount++;
        statusShadowRow = rowCount++;

        peersHeaderRow = rowCount++;
        if (!peersList.isEmpty()) {
            peersStartRow = rowCount;
            peersEndRow = peersStartRow + peersList.size();
            rowCount = peersEndRow;
            noPeersRow = -1;
        } else {
            peersStartRow = -1;
            peersEndRow = -1;
            noPeersRow = rowCount++;
        }
        peersShadowRow = rowCount++;

        configHeaderRow = rowCount++;
        if (!configPeersList.isEmpty()) {
            configStartRow = rowCount;
            configEndRow = configStartRow + configPeersList.size();
            rowCount = configEndRow;
            noConfigRow = -1;
        } else {
            configStartRow = -1;
            configEndRow = -1;
            noConfigRow = rowCount++;
        }
        configShadowRow = rowCount++;

        retryPeersRow = rowCount++;
        retryPeersShadowRow = rowCount++;

        pingedHeaderRow = rowCount++;
        if (!pingedPeersList.isEmpty()) {
            pingedStartRow = rowCount;
            pingedEndRow = pingedStartRow + pingedPeersList.size();
            rowCount = pingedEndRow;
            noPingedRow = -1;
        } else {
            pingedStartRow = -1;
            pingedEndRow = -1;
            noPingedRow = rowCount++;
        }
        pingedShadowRow = rowCount++;
    }

    private void retryPeers() {
        Yggstack ygg = ApplicationLoader.yggInstance;
        if (ygg != null) {
            try {
                ygg.retryPeersNow();
            } catch (Exception ignored) {
            }
        }
        if (ApplicationLoader.isScanningPeers) {
            Toast.makeText(getParentActivity(), "Scan already running", Toast.LENGTH_SHORT).show();
            return;
        }
        ApplicationLoader.lastPingedPeers = new java.util.ArrayList<>();
        ApplicationLoader.isScanningPeers = true;
        ApplicationLoader.scanProgress = 0;
        ApplicationLoader.scanTotal = 0;
        new Thread(() -> {
            try {
                link.yggdrasil.yggstack.android.service.PeerFetcherService fetcher = new link.yggdrasil.yggstack.android.service.PeerFetcherService();
                java.util.List<link.yggdrasil.yggstack.android.data.PublicPeerInfo> peers = fetcher.fetchPublicPeersBlocking();
                if (peers == null || peers.isEmpty()) {
                    ApplicationLoader.isScanningPeers = false;
                    AndroidUtilities.runOnUIThread(() ->
                        Toast.makeText(getParentActivity(), "No public peers found", Toast.LENGTH_SHORT).show()
                    );
                    return;
                }
                ApplicationLoader.scanTotal = peers.size();
                link.yggdrasil.yggstack.android.service.PeerPingerService pinger = new link.yggdrasil.yggstack.android.service.PeerPingerService();
                kotlin.coroutines.EmptyCoroutineContext ctx = kotlin.coroutines.EmptyCoroutineContext.INSTANCE;
                kotlinx.coroutines.BuildersKt.runBlocking(ctx, (scope, continuation) ->
                    pinger.checkPeersByHostWithProgress(peers, (checked, total) -> {
                        ApplicationLoader.scanProgress = checked;
                        return kotlin.Unit.INSTANCE;
                    }, updatedList -> {
                        ApplicationLoader.lastPingedPeers = new java.util.ArrayList<>(updatedList);
                        return kotlin.Unit.INSTANCE;
                    }, continuation)
                );
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() ->
                    Toast.makeText(getParentActivity(), "Scan failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            } finally {
                ApplicationLoader.isScanningPeers = false;
            }
        }).start();
    }

    private void connectToPingedPeer(int index) {
        java.util.List<link.yggdrasil.yggstack.android.data.PublicPeerInfo> pinged = ApplicationLoader.lastPingedPeers;
        if (pinged == null || index < 0 || index >= pinged.size()) return;
        if (ApplicationLoader.yggInstance == null) {
            Toast.makeText(getParentActivity(), "Yggdrasil not running", Toast.LENGTH_SHORT).show();
            return;
        }
        link.yggdrasil.yggstack.android.data.PublicPeerInfo peer = pinged.get(index);
        ApplicationLoader.switchToPeer(peer.getUri());
        Toast.makeText(getParentActivity(), "Connecting to " + peer.getUri(), Toast.LENGTH_SHORT).show();
    }

    private static String formatUptime(double seconds) {
        long s = (long) seconds;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == retryPeersRow
                    || position == statusRow
                    || position == addressRow
                    || position == publicKeyRow
                    || (position >= peersStartRow && position < peersEndRow)
                    || (position >= configStartRow && position < configEndRow)
                    || (position >= pingedStartRow && position < pingedEndRow);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_TEXT_DETAIL:
                    view = new TextDetailSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_TEXT_SETTING:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                default:
                    view = new TextInfoPrivacyCell(mContext);
                    view.setBackground(Theme.getThemedDrawableByKey(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();

            if (viewType == VIEW_TYPE_HEADER) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                if (position == statusHeaderRow) {
                    cell.setText("Status");
                } else if (position == peersHeaderRow) {
                    cell.setText("Connected Peers");
                } else if (position == configHeaderRow) {
                    cell.setText("Config Peers");
                } else if (position == pingedHeaderRow) {
                    if (ApplicationLoader.isScanningPeers) {
                        cell.setText("Pinged Peers (" + ApplicationLoader.scanProgress + "/" + ApplicationLoader.scanTotal + ")");
                    } else if (!pingedPeersList.isEmpty()) {
                        cell.setText("Pinged Peers (" + pingedPeersList.size() + ")");
                    } else {
                        cell.setText("Pinged Peers");
                    }
                }
            } else if (viewType == VIEW_TYPE_TEXT_DETAIL) {
                TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                if (position == statusRow) {
                    cell.setTextAndValue("Connection", statusString, true);
                    cell.getValueTextView().setTextColor(Theme.getColor(
                            statusRunning ? Theme.key_windowBackgroundWhiteGreenText : Theme.key_text_RedRegular
                    ));
                } else if (position == addressRow) {
                    cell.setTextAndValue("IPv6 Address", addressString, true);
                    cell.getValueTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                } else if (position == publicKeyRow) {
                    cell.setMultilineDetail(true);
                    cell.setTextAndValue("Public Key", publicKeyString, false);
                    cell.getValueTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                } else if (position >= peersStartRow && position < peersEndRow) {
                    int idx = position - peersStartRow;
                    boolean divider = position < peersEndRow - 1;
                    cell.setMultilineDetail(true);
                    cell.setTextAndValue("Peer " + (idx + 1), peersList.get(idx), divider);
                    cell.getValueTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                } else if (position >= configStartRow && position < configEndRow) {
                    int idx = position - configStartRow;
                    boolean divider = position < configEndRow - 1;
                    cell.setMultilineDetail(true);
                    cell.setTextAndValue("Peer " + (idx + 1), configPeersList.get(idx), divider);
                    cell.getValueTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                } else if (position >= pingedStartRow && position < pingedEndRow) {
                    int idx = position - pingedStartRow;
                    boolean divider = position < pingedEndRow - 1;
                    cell.setMultilineDetail(true);
                    cell.setTextAndValue("#" + (idx + 1), pingedPeersList.get(idx), divider);
                    cell.getValueTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                }
            } else if (viewType == VIEW_TYPE_TEXT_SETTING) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (position == retryPeersRow) {
                    if (ApplicationLoader.isScanningPeers) {
                        cell.setText("Scanning... (" + ApplicationLoader.scanProgress + "/" + ApplicationLoader.scanTotal + ")", false);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                    } else {
                        cell.setText("Scan Peers", false);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText6));
                    }
                }
            } else if (viewType == VIEW_TYPE_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (position == noPeersRow) {
                    cell.setText("No peers connected");
                } else if (position == noConfigRow) {
                    cell.setText("No config peers");
                } else if (position == noPingedRow) {
                    cell.setText("No pinged peers yet");
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == statusShadowRow || position == peersShadowRow || position == configShadowRow || position == retryPeersShadowRow || position == pingedShadowRow) {
                return VIEW_TYPE_SHADOW;
            } else if (position == statusHeaderRow || position == peersHeaderRow || position == configHeaderRow || position == pingedHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == retryPeersRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == noPeersRow || position == noConfigRow || position == noPingedRow) {
                return VIEW_TYPE_INFO;
            } else {
                return VIEW_TYPE_TEXT_DETAIL;
            }
        }
    }
}
