package com.example.onstepcontroller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.SocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class MainActivity extends Activity {
    private static final int DEFAULT_PORT = 9999;
    private static final String JPL_SBDB_HOST = "ssd-api.jpl.nasa.gov";
    private static final String PREFS_NAME = "mountbehave_prefs";
    private static final String PREF_FIRMWARE_MODE = "firmware_mode";
    private static final String PREF_MOUNT_MODE = "mount_mode";
    private static final String PREF_LOG_PRIVACY_ACCEPTED_AT = "log_privacy_accepted_at";
    private static final long LOG_PRIVACY_ACK_VALID_MS = 24L * 60L * 60L * 1000L;
    private static final String ONSTEPX_MOUNT_MODE_QUERY = ":GXEM#";
    private static final int SIDE_MENU_EXPANDED_WIDTH_DP = 148;
    private static final int SIDE_MENU_COLLAPSED_SIZE_DP = 56;
    private static final int SIDE_MENU_MARGIN_START_DP = 8;
    private static final int SIDE_MENU_MARGIN_TOP_DP = 22;
    private static final int SIDE_MENU_TOGGLE_HEIGHT_DP = 48;
    private static final int SIDE_MENU_ITEM_TOP_MARGIN_DP = 10;
    private static final int SIDE_MENU_ITEM_HEIGHT_DP = 54;
    private static final int SIDE_MENU_VERSION_TOP_MARGIN_DP = 12;
    private static final int SIDE_MENU_VERSION_HEIGHT_DP = 28;
    private static final int SIDE_MENU_FLOATING_STOP_GAP_DP = 8;
    private static final int FLOATING_STOP_COLLAPSED_TOP_MARGIN_DP = 86;
    private static final int SIDE_MENU_ITEM_COUNT = 4;
    private static final int LOCATION_PERMISSION_REQUEST = 24;
    private static final long CONNECTION_POLL_INTERVAL_MS = 5_000L;
    private static final Pattern COORDINATE_TARGET_PATTERN = Pattern.compile(
            "^\\s*(?:RA\\s*)?([0-9]{1,2}(?::[0-9]{1,2}(?::[0-9]{1,2}(?:\\.\\d+)?)?)?|[0-9]+(?:\\.\\d+)?)\\s*[, ]+\\s*(?:DEC\\s*)?([+-]?[0-9]{1,2}(?:(?::|\\*|°)[0-9]{1,2}(?:(?::|')?[0-9]{1,2}(?:\\.\\d+)?)?)?|[+-]?[0-9]+(?:\\.\\d+)?)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final OnStepClient client = new OnStepClient();
    private final AtomicInteger connectionGeneration = new AtomicInteger();
    private static volatile SSLContext relaxedJplSslContext;
    private final Runnable skyClockRunnable = new Runnable() {
        @Override
        public void run() {
            updateSkyTime();
            uiHandler.postDelayed(this, CONNECTION_POLL_INTERVAL_MS);
        }
    };

    private Button manualTabButton;
    private Button skyTabButton;
    private Button settingsTabButton;
    private Button connectionSyncTabButton;
    private Button sideMenuToggleButton;
    private Button floatingStopButton;
    private TextView sideMenuVersionText;
    private LinearLayout sideMenu;
    private LinearLayout manualPage;
    private LinearLayout skyPage;
    private LinearLayout settingsPage;
    private LinearLayout connectionSyncPage;
    private EditText hostField;
    private EditText portField;
    private LinearLayout connectionForm;
    private View connectTrigger;
    private Button disconnectButton;
    private Spinner firmwareModeSpinner;
    private Spinner mountModeSpinner;
    private LinearLayout mountModeContainer;
    private TextView firmwareSettingsStatusText;
    private Button stopButton;
    private Button northButton;
    private Button northEastButton;
    private Button northWestButton;
    private Button southButton;
    private Button southEastButton;
    private Button southWestButton;
    private Button eastButton;
    private Button westButton;
    private Spinner manualRateSpinner;
    private ObserverState observerState = ObserverState.boston();
    private EditText latitudeField;
    private EditText longitudeField;
    private TextView observerStatusText;
    private TextView timeStatusText;
    private SkyChartView skyChartView;
    private TextView skySummaryText;
    private TextView targetStatusText;
    private TextView mountPointingText;
    private TextView gotoStatusText;
    private TextView observingAlertText;
    private Button gotoButton;
    private Button skyCancelGotoButton;
    private Button syncMountButton;
    private Button trackingSiderealButton;
    private Button trackingLunarButton;
    private Button trackingSolarButton;
    private Button trackingToggleButton;
    private TextView trackingStatusText;
    private Button gotoHomeButton;
    private Button setHomeButton;
    private TextView safetyStatusText;
    private Button emergencyStopButton;
    private Button safetyCancelGotoButton;
    private Button gotoStatusRefreshButton;
    private Button parkButton;
    private Button unparkButton;
    private Button nightModeButton;
    private Spinner calibrationModeSpinner;
    private LinearLayout quickCalibrationPanel;
    private LinearLayout alignCalibrationPanel;
    private LinearLayout refineCalibrationPanel;
    private EditText calibrationTargetField;
    private TextView calibrationStatusText;
    private TextView calibrationStepText;
    private TextView alignmentCurrentText;
    private TextView alignmentAcceptedText;
    private Button calibrationSuggestButton;
    private Button calibrationShowButton;
    private Button quickSelectButton;
    private Button quickSyncButton;
    private Button alignStartButton;
    private Button alignSelectButton;
    private Button alignGotoButton;
    private Button alignAcceptButton;
    private Button alignSaveButton;
    private Button alignCancelButton;
    private Button refineGotoButton;
    private Button refinePaButton;
    private TextView statusText;
    private TextView manualStatusText;
    private TextView logText;
    private ScrollView logScrollView;
    private LocationManager locationManager;
    private ConnectivityManager connectivityManager;
    private WifiManager.WifiLock wifiLock;
    private Network boundWifiNetwork;

    private volatile boolean connected;
    private volatile boolean busy;
    private boolean sideMenuExpanded;
    private boolean nightModeEnabled;
    private volatile boolean gotoInProgress;
    private boolean parked;
    private String connectedHost;
    private int connectedPort = DEFAULT_PORT;
    private String currentStatusMessage;
    private String gotoStatusMessage;
    private String safetyStatusMessage;
    private int mountPointingFailureCount;
    private boolean mountPointingPollingPaused;
    private boolean hasCurrentMountPosition;
    private double currentMountRaHours;
    private double currentMountDecDegrees;
    private boolean trackingEnabled;
    private boolean hasAlignmentTrackingModel;
    private int savedAlignmentStarCount;
    private boolean trackingUsingDualAxis;
    private TrackingRate selectedTrackingRate = TrackingRate.SIDEREAL;
    private volatile Direction activeDirection;
    private SkyChartView.Target selectedSkyTarget;
    private SkyChartView.Target calibrationTarget;
    private SkyChartView.Target syncedCurrentTarget;
    private SkyChartView.Target polarRefineSyncedTarget;
    private boolean selectingCalibrationTargetFromSky;
    private AlertDialog calibrationTargetConfirmDialog;
    private boolean quickPointingCorrectionActive;
    private double quickPointingRaOffsetHours;
    private double quickPointingDecOffsetDegrees;
    private boolean homeFindInProgress;
    private ManualRate selectedManualRate = ManualRate.CENTER;
    private CalibrationMode selectedCalibrationMode = CalibrationMode.QUICK_SYNC;
    private FirmwareMode selectedFirmwareMode = FirmwareMode.ONSTEP;
    private MountMode selectedMountMode = MountMode.EQUATORIAL;
    private Page currentPage = Page.SETTINGS;
    private volatile AlignmentSession alignmentSession;
    private int suggestedCalibrationIndex;
    private SmallBodyCatalog smallBodyCatalog;
    private TextView smallBodyStatusText;
    private Button smallBodyDownloadAsteroidsButton;
    private Button smallBodyDownloadCometsButton;
    private Button smallBodyClearUserButton;
    private boolean suppressCalibrationModeSelection;
    private boolean suppressFirmwareModeSelection;
    private boolean suppressMountModeSelection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.init(getApplicationContext());
        Logger.setUiCallback(this::updateLogText);
        Logger.info("lifecycle onCreate");
        connectivityManager = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyNightModeWindow();
        loadFirmwarePreferences();
        smallBodyCatalog = new SmallBodyCatalog(getFilesDir());
        setContentView(createContentView());
        updateUiState();
        updateObserverViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logger.info("lifecycle onResume");
        acquireWifiLock();
        uiHandler.post(skyClockRunnable);
        if (connected && !busy) {
            refreshGotoStatus();
            refreshMountPointing();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.info("lifecycle onPause");
        uiHandler.removeCallbacks(skyClockRunnable);
        if (connected && activeDirection != null) {
            activeDirection = null;
            enqueueStop(getString(R.string.log_auto_stop_background));
        }
        releaseWifiLock();
    }

    @Override
    protected void onDestroy() {
        Logger.info("lifecycle onDestroy");
        ioExecutor.execute(() -> {
            try {
                if (client.isConnected()) {
                    client.sendNoReply(OnStepCommand.STOP_ALL.command);
                }
            } catch (IOException ignored) {
                // The app is closing; nothing useful can be shown here.
            } finally {
                client.close();
            }
        });
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        releaseWifiLock();
        releaseWifiBinding();
        Logger.setUiCallback(null);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) {
            return;
        }
        boolean granted = false;
        for (int result : grantResults) {
            granted = granted || result == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            Logger.info("gps permission result granted");
            useGpsLocation();
        } else {
            Logger.warn("gps permission result denied");
            setObserverMessage(getString(R.string.gps_permission_denied));
        }
    }

    private View createContentView() {
        boolean wideLayout = isWideLayout();
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(pageBackgroundColor());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(pageBackgroundColor());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(wideLayout ? 20 : 10), dp(14), dp(wideLayout ? 20 : 10), dp(18));
        scrollView.addView(root, matchWrap());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(60), 0, 0, dp(8));
        TextView title = titleText(R.string.app_name, 23);
        header.addView(title, matchWrap());

        TextView subtitle = bodyText(R.string.app_subtitle);
        subtitle.setPadding(0, dp(4), 0, 0);
        header.addView(subtitle, matchWrap());
        root.addView(header, matchWrap());

        settingsPage = createSettingsPage();
        root.addView(settingsPage, matchWrap());

        connectionSyncPage = createConnectionSyncPage();
        connectionSyncPage.setVisibility(View.GONE);
        root.addView(connectionSyncPage, matchWrap());

        manualPage = new LinearLayout(this);
        manualPage.setOrientation(LinearLayout.VERTICAL);
        manualStatusText = bodyText(R.string.status_disconnected);
        manualStatusText.setTextColor(labelTextColor());
        manualStatusText.setBackgroundColor(cardBackgroundColor());
        manualStatusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (currentStatusMessage != null) {
            manualStatusText.setText(currentStatusMessage);
        }
        manualPage.addView(manualStatusText, matchWrap());
        manualPage.addView(createCalibrationPage(), matchWrapWithTopMargin(8));
        manualPage.addView(sectionTitle(R.string.manual_control_section), matchWrapWithTopMargin(8));
        manualPage.addView(createControlPanel(), matchWrap());
        root.addView(manualPage, matchWrap());

        skyPage = createSkyPage();
        skyPage.setVisibility(View.GONE);
        root.addView(skyPage, matchWrap());

        shell.addView(scrollView, frameMatchParent());
        shell.addView(createPageTabs(), sideMenuParams());
        shell.addView(createFloatingEmergencyStopButton(), floatingStopParams());

        updatePageTabs(currentPage);
        return shell;
    }

    private View createPageTabs() {
        sideMenu = new LinearLayout(this);
        sideMenu.setOrientation(LinearLayout.VERTICAL);
        sideMenu.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sideMenu.setElevation(dp(8));

        sideMenuToggleButton = new Button(this);
        configureMenuToggleButton(sideMenuToggleButton);
        sideMenuToggleButton.setOnClickListener(v -> setSideMenuExpanded(!sideMenuExpanded));
        sideMenu.addView(sideMenuToggleButton, sideMenuToggleParams());

        settingsTabButton = new Button(this);
        configureTabButton(settingsTabButton);
        settingsTabButton.setText(R.string.tab_settings);
        settingsTabButton.setOnClickListener(v -> selectPageFromMenu(Page.SETTINGS));
        sideMenu.addView(settingsTabButton, sideMenuButtonParams(SIDE_MENU_ITEM_TOP_MARGIN_DP));

        connectionSyncTabButton = new Button(this);
        configureTabButton(connectionSyncTabButton);
        connectionSyncTabButton.setText(R.string.tab_connection_sync);
        connectionSyncTabButton.setOnClickListener(v -> selectPageFromMenu(Page.CONNECTION_SYNC));
        sideMenu.addView(connectionSyncTabButton, sideMenuButtonParams(SIDE_MENU_ITEM_TOP_MARGIN_DP));

        manualTabButton = new Button(this);
        configureTabButton(manualTabButton);
        manualTabButton.setText(R.string.tab_manual);
        manualTabButton.setOnClickListener(v -> selectPageFromMenu(Page.MANUAL));
        sideMenu.addView(manualTabButton, sideMenuButtonParams(SIDE_MENU_ITEM_TOP_MARGIN_DP));

        skyTabButton = new Button(this);
        configureTabButton(skyTabButton);
        skyTabButton.setText(R.string.tab_sky);
        skyTabButton.setOnClickListener(v -> selectPageFromMenu(Page.SKY));
        sideMenu.addView(skyTabButton, sideMenuButtonParams(SIDE_MENU_ITEM_TOP_MARGIN_DP));

        sideMenuVersionText = new TextView(this);
        sideMenuVersionText.setText(getString(R.string.version_info, appVersionName()));
        sideMenuVersionText.setTextSize(12);
        sideMenuVersionText.setTextColor(mutedTextColor());
        sideMenuVersionText.setGravity(Gravity.CENTER);
        sideMenu.addView(sideMenuVersionText, sideMenuVersionParams());

        setSideMenuExpanded(sideMenuExpanded);
        return sideMenu;
    }

    private View createFloatingEmergencyStopButton() {
        floatingStopButton = new Button(this);
        floatingStopButton.setAllCaps(false);
        floatingStopButton.setText(R.string.emergency_stop_short);
        floatingStopButton.setTextSize(14);
        floatingStopButton.setTextColor(Color.WHITE);
        floatingStopButton.setTypeface(Typeface.DEFAULT_BOLD);
        floatingStopButton.setBackground(createStopButtonBackground());
        floatingStopButton.setVisibility(View.GONE);
        floatingStopButton.setOnClickListener(v -> emergencyStop());
        return floatingStopButton;
    }

    private LinearLayout createSkyPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        page.addView(sectionTitleWithHelp(R.string.sky_section, R.string.sky_planet_note), matchWrap());

        LinearLayout panel = card();
        skySummaryText = bodyText(R.string.sky_loading);
        compactSkyText(skySummaryText);
        skySummaryText.setPadding(0, 0, 0, dp(4));
        panel.addView(skySummaryText, matchWrap());

        skyChartView = new SkyChartView(this);
        skyChartView.setObserver(observerState, Instant.now());
        skyChartView.setSmallBodyCatalog(smallBodyCatalog);
        skyChartView.setTargetSelectionListener(target -> {
            selectedSkyTarget = target;
            updateTargetViews();
            if (selectingCalibrationTargetFromSky) {
                handleCalibrationTargetSelectedInSky(target);
            }
        });
        skyChartView.setViewStateListener(() -> skySummaryText.setText(skyChartView.summary()));
        if (selectedSkyTarget != null) {
            skyChartView.setSelectedTarget(selectedSkyTarget, false);
        }
        if (hasCurrentMountPosition) {
            skyChartView.setMountEquatorial(currentMountRaHours, currentMountDecDegrees);
        }
        skySummaryText.setText(skyChartView.summary());

        targetStatusText = bodyText(R.string.sky_target_none);
        compactSkyText(targetStatusText);
        targetStatusText.setPadding(0, 0, 0, 0);
        panel.addView(targetStatusText, matchWrap());

        mountPointingText = bodyText(R.string.mount_pointing_default);
        compactSkyText(mountPointingText);
        if (hasCurrentMountPosition) {
            mountPointingText.setText(getString(
                    R.string.mount_pointing_status,
                    formatRightAscensionDisplay(currentMountRaHours),
                    formatDeclinationDisplay(currentMountDecDegrees)
            ));
        }

        gotoStatusText = bodyText(R.string.goto_status_idle);
        compactSkyText(gotoStatusText);
        if (gotoStatusMessage != null) {
            gotoStatusText.setText(gotoStatusMessage);
        }

        LinearLayout skyStatusRow = new LinearLayout(this);
        skyStatusRow.setOrientation(LinearLayout.HORIZONTAL);
        skyStatusRow.setGravity(Gravity.CENTER_VERTICAL);
        skyStatusRow.setPadding(0, dp(2), 0, 0);
        skyStatusRow.addView(mountPointingText, weightWrap(1f));
        skyStatusRow.addView(gotoStatusText, weightWrapWithLeftMargin(1f, 8));
        panel.addView(skyStatusRow, matchWrap());

        observingAlertText = bodyText(R.string.observing_alert_no_target);
        compactSkyText(observingAlertText);
        observingAlertText.setPadding(0, dp(2), 0, 0);
        panel.addView(observingAlertText, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button resetButton = new Button(this);
        resetButton.setAllCaps(false);
        compactButton(resetButton);
        resetButton.setText(R.string.sky_reset);
        resetButton.setOnClickListener(v -> {
            skyChartView.resetView();
            skySummaryText.setText(skyChartView.summary());
        });
        actions.addView(resetButton, weightWrap(1f));

        gotoButton = new Button(this);
        gotoButton.setAllCaps(false);
        compactButton(gotoButton);
        gotoButton.setText(R.string.sky_goto_target);
        gotoButton.setOnClickListener(v -> showTargetDialog());
        actions.addView(gotoButton, weightWrapWithLeftMargin(1f, 6));

        skyCancelGotoButton = new Button(this);
        skyCancelGotoButton.setAllCaps(false);
        compactButton(skyCancelGotoButton);
        skyCancelGotoButton.setText(R.string.goto_cancel);
        skyCancelGotoButton.setOnClickListener(v -> cancelGoto());
        actions.addView(skyCancelGotoButton, weightWrapWithLeftMargin(1f, 6));

        Button layersButton = new Button(this);
        layersButton.setAllCaps(false);
        compactButton(layersButton);
        layersButton.setText(R.string.sky_layers);
        layersButton.setOnClickListener(v -> showLayerDialog());
        actions.addView(layersButton, weightWrapWithLeftMargin(1f, 6));

        panel.addView(actions, matchWrap());

        panel.addView(skyChartView, matchFixedHeight(isWideLayout() ? 620 : 480));

        page.addView(panel, matchWrap());
        return page;
    }

    private LinearLayout createCalibrationPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout calibrationPanel = card();
        calibrationPanel.addView(panelTitleWithHelp(R.string.calibration_section, R.string.calibration_intro), matchWrap());

        calibrationPanel.addView(labelText(R.string.calibration_mode_label), matchWrap());
        calibrationModeSpinner = new Spinner(this);
        refreshCalibrationModeChoices();
        calibrationPanel.addView(calibrationModeSpinner, matchWrap());

        TextView targetLabel = labelText(R.string.calibration_target_label);
        targetLabel.setPadding(0, dp(4), 0, 0);
        calibrationPanel.addView(targetLabel, matchWrap());
        calibrationTargetField = compactEditText();
        calibrationTargetField.setInputType(InputType.TYPE_CLASS_TEXT);
        calibrationTargetField.setHint(R.string.calibration_target_hint);
        calibrationTargetField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // No-op.
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCalibrationTargetActionButton();
            }

            @Override
            public void afterTextChanged(Editable s) {
                // No-op.
            }
        });
        calibrationPanel.addView(calibrationTargetField, matchWrap());

        LinearLayout targetActions = new LinearLayout(this);
        targetActions.setOrientation(LinearLayout.HORIZONTAL);
        targetActions.setGravity(Gravity.CENTER_VERTICAL);
        targetActions.setPadding(0, dp(6), 0, 0);

        calibrationSuggestButton = actionButton(R.string.calibration_suggest_star);
        calibrationSuggestButton.setOnClickListener(v -> fillSuggestedCalibrationTarget());
        targetActions.addView(calibrationSuggestButton, weightWrap(1f));

        calibrationShowButton = actionButton(R.string.calibration_show_in_sky);
        calibrationShowButton.setOnClickListener(v -> handleCalibrationTargetAction());
        targetActions.addView(calibrationShowButton, weightWrapWithLeftMargin(1f, 8));
        updateCalibrationTargetActionButton();

        calibrationPanel.addView(targetActions, matchWrap());

        calibrationStatusText = bodyText(R.string.calibration_status_idle);
        calibrationStatusText.setPadding(0, dp(4), 0, 0);
        calibrationStatusText.setVisibility(View.GONE);
        calibrationPanel.addView(calibrationStatusText, matchWrap());

        quickCalibrationPanel = modePanel();
        quickCalibrationPanel.addView(panelTitleWithHelp(
                R.string.calibration_quick_section,
                R.string.calibration_quick_intro
        ), matchWrap());

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.CENTER_VERTICAL);

        quickSelectButton = actionButton(R.string.calibration_quick_select);
        quickSelectButton.setOnClickListener(v -> selectQuickCalibrationTarget());
        quickActions.addView(quickSelectButton, weightWrap(1f));

        quickSyncButton = actionButton(R.string.calibration_quick_sync);
        quickSyncButton.setOnClickListener(v -> syncQuickCalibrationTarget());
        quickActions.addView(quickSyncButton, weightWrapWithLeftMargin(1f, 8));

        quickCalibrationPanel.addView(quickActions, matchWrap());
        calibrationPanel.addView(quickCalibrationPanel, matchWrap());

        alignCalibrationPanel = modePanel();
        alignCalibrationPanel.addView(panelTitleWithHelp(
                R.string.calibration_align_section,
                R.string.calibration_align_intro
        ), matchWrap());

        calibrationStepText = bodyText(R.string.calibration_align_idle);
        calibrationStepText.setPadding(0, 0, 0, dp(4));
        alignCalibrationPanel.addView(calibrationStepText, matchWrap());

        alignmentCurrentText = bodyText(R.string.calibration_align_current_none);
        alignmentCurrentText.setPadding(0, 0, 0, dp(4));
        alignCalibrationPanel.addView(alignmentCurrentText, matchWrap());

        alignmentAcceptedText = bodyText(R.string.calibration_align_accepted_none);
        alignmentAcceptedText.setPadding(0, 0, 0, dp(6));
        alignCalibrationPanel.addView(alignmentAcceptedText, matchWrap());

        alignStartButton = actionButton(R.string.calibration_align_start);
        alignStartButton.setOnClickListener(v -> {
            if (selectedCalibrationMode.starCount > 0) {
                startAlignment(selectedCalibrationMode.starCount);
            }
        });
        alignCalibrationPanel.addView(alignStartButton, matchWrapWithTopMargin(4));

        LinearLayout alignActionsOne = new LinearLayout(this);
        alignActionsOne.setOrientation(LinearLayout.HORIZONTAL);
        alignActionsOne.setGravity(Gravity.CENTER_VERTICAL);

        alignSelectButton = actionButton(R.string.calibration_align_select_current);
        alignSelectButton.setOnClickListener(v -> selectAlignmentTargetOnly());
        alignActionsOne.addView(alignSelectButton, weightWrap(1f));

        alignGotoButton = actionButton(R.string.calibration_align_goto);
        alignGotoButton.setOnClickListener(v -> gotoAlignmentTarget());
        alignActionsOne.addView(alignGotoButton, weightWrapWithLeftMargin(1f, 8));

        alignCalibrationPanel.addView(alignActionsOne, matchWrapWithTopMargin(6));

        LinearLayout alignActionsTwo = new LinearLayout(this);
        alignActionsTwo.setOrientation(LinearLayout.VERTICAL);
        alignActionsTwo.setGravity(Gravity.CENTER_VERTICAL);
        alignActionsTwo.setPadding(0, dp(6), 0, 0);

        alignAcceptButton = actionButton(R.string.calibration_align_accept);
        alignAcceptButton.setOnClickListener(v -> acceptAlignmentStar());
        alignActionsTwo.addView(alignAcceptButton, matchWrap());

        LinearLayout alignFinishActions = new LinearLayout(this);
        alignFinishActions.setOrientation(LinearLayout.HORIZONTAL);
        alignFinishActions.setGravity(Gravity.CENTER_VERTICAL);
        alignFinishActions.setPadding(0, dp(6), 0, 0);

        alignSaveButton = actionButton(R.string.calibration_align_save);
        alignSaveButton.setOnClickListener(v -> saveAlignmentModel());
        alignFinishActions.addView(alignSaveButton, weightWrap(1f));

        alignCancelButton = actionButton(R.string.calibration_align_cancel);
        alignCancelButton.setOnClickListener(v -> cancelAlignmentSession());
        alignFinishActions.addView(alignCancelButton, weightWrapWithLeftMargin(1f, 8));

        alignActionsTwo.addView(alignFinishActions, matchWrap());

        alignCalibrationPanel.addView(alignActionsTwo, matchWrap());
        calibrationPanel.addView(alignCalibrationPanel, matchWrap());

        refineCalibrationPanel = modePanel();
        refineCalibrationPanel.addView(panelTitleWithHelp(
                R.string.calibration_mode_refine_polar,
                R.string.calibration_refine_intro
        ), matchWrap());

        refineGotoButton = actionButton(R.string.calibration_refine_goto);
        refineGotoButton.setOnClickListener(v -> gotoRefinePolarTarget());
        refineCalibrationPanel.addView(refineGotoButton, matchWrap());

        refinePaButton = actionButton(R.string.calibration_refine_pa);
        refinePaButton.setOnClickListener(v -> refinePolarAlignment());
        refineCalibrationPanel.addView(refinePaButton, matchWrapWithTopMargin(6));

        calibrationPanel.addView(refineCalibrationPanel, matchWrap());
        page.addView(calibrationPanel, matchWrap());
        updateCalibrationModeViews();
        return page;
    }

    private LinearLayout createSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(sectionTitleWithHelp(R.string.firmware_settings_section, R.string.firmware_settings_intro), matchWrap());
        page.addView(createFirmwareSettingsPanel(), matchWrap());

        addAdvancedSettingsSections(page);
        return page;
    }

    private LinearLayout createConnectionSyncPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        if (isWideLayout()) {
            LinearLayout columns = new LinearLayout(this);
            columns.setOrientation(LinearLayout.HORIZONTAL);
            columns.setGravity(Gravity.TOP);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(sectionTitle(R.string.connection_section), matchWrap());
            left.addView(createConnectionPanel(), matchWrap());
            left.addView(sectionTitle(R.string.observer_section), matchWrapWithTopMargin(8));
            left.addView(createObserverPanel(), matchWrap());
            columns.addView(left, weightWrap(1f));

            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.addView(sectionTitleWithHelp(R.string.tracking_section, R.string.tracking_intro), matchWrap());
            right.addView(createTrackingPanel(), matchWrap());
            columns.addView(right, weightWrapWithLeftMargin(1f, 12));

            page.addView(columns, matchWrap());
        } else {
            page.addView(sectionTitle(R.string.connection_section), matchWrap());
            page.addView(createConnectionPanel(), matchWrap());

            page.addView(sectionTitle(R.string.observer_section), matchWrapWithTopMargin(8));
            page.addView(createObserverPanel(), matchWrap());

            page.addView(sectionTitleWithHelp(R.string.tracking_section, R.string.tracking_intro), matchWrapWithTopMargin(8));
            page.addView(createTrackingPanel(), matchWrap());
        }

        return page;
    }

    private void addAdvancedSettingsSections(LinearLayout parent) {
        parent.addView(sectionTitleWithHelp(R.string.safety_section, R.string.safety_intro), matchWrapWithTopMargin(8));
        parent.addView(createSafetyPanel(), matchWrap());
        parent.addView(sectionTitleWithHelp(R.string.small_bodies_section, R.string.small_bodies_intro), matchWrapWithTopMargin(8));
        parent.addView(createSmallBodiesPanel(), matchWrap());
        parent.addView(sectionTitle(R.string.command_log_section), matchWrapWithTopMargin(8));
        parent.addView(createCommandLogPanel(), matchWrap());
    }

    private View createFirmwareSettingsPanel() {
        LinearLayout panel = card();

        panel.addView(labelText(R.string.firmware_mode_label), matchWrap());
        firmwareModeSpinner = new Spinner(this);
        List<String> firmwareLabels = new ArrayList<>();
        for (FirmwareMode mode : FirmwareMode.values()) {
            firmwareLabels.add(getString(mode.labelRes));
        }
        ArrayAdapter<String> firmwareAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                firmwareLabels
        );
        firmwareAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        firmwareModeSpinner.setAdapter(firmwareAdapter);
        firmwareModeSpinner.setSelection(selectedFirmwareMode.ordinal());
        firmwareModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressFirmwareModeSelection) {
                    return;
                }
                FirmwareMode mode = FirmwareMode.values()[position];
                if (selectedFirmwareMode == mode) {
                    return;
                }
                logUserAction("select firmware-mode " + mode.name());
                selectedFirmwareMode = mode;
                if (selectedFirmwareMode == FirmwareMode.ONSTEP && selectedMountMode == MountMode.ALTAZ) {
                    selectedMountMode = MountMode.EQUATORIAL;
                }
                saveFirmwarePreferences();
                updateFirmwareSettingsViews();
                refreshCalibrationModeChoices();
                updateCalibrationViews();
                updateTrackingViews();
                logStateSnapshot("firmware-mode-selected");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        panel.addView(firmwareModeSpinner, matchWrap());

        mountModeContainer = new LinearLayout(this);
        mountModeContainer.setOrientation(LinearLayout.VERTICAL);
        mountModeContainer.setPadding(0, dp(8), 0, 0);
        mountModeContainer.addView(labelText(R.string.mount_mode_label), matchWrap());
        mountModeSpinner = new Spinner(this);
        List<String> mountLabels = new ArrayList<>();
        for (MountMode mode : MountMode.values()) {
            mountLabels.add(getString(mode.labelRes));
        }
        ArrayAdapter<String> mountAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                mountLabels
        );
        mountAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mountModeSpinner.setAdapter(mountAdapter);
        mountModeSpinner.setSelection(selectedMountMode.ordinal());
        mountModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressMountModeSelection) {
                    return;
                }
                MountMode requestedMode = MountMode.values()[position];
                if (selectedMountMode == requestedMode) {
                    return;
                }
                requestMountModeChange(requestedMode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        mountModeContainer.addView(mountModeSpinner, matchWrap());
        panel.addView(mountModeContainer, matchWrap());

        firmwareSettingsStatusText = bodyText(R.string.firmware_settings_status_onstep);
        firmwareSettingsStatusText.setPadding(0, dp(8), 0, 0);
        panel.addView(firmwareSettingsStatusText, matchWrap());

        updateFirmwareSettingsViews();
        return panel;
    }

    private View createSafetyPanel() {
        LinearLayout panel = card();

        LinearLayout stopActions = new LinearLayout(this);
        stopActions.setOrientation(LinearLayout.HORIZONTAL);
        stopActions.setGravity(Gravity.CENTER_VERTICAL);

        emergencyStopButton = actionButton(R.string.emergency_stop);
        emergencyStopButton.setTextColor(Color.WHITE);
        emergencyStopButton.setTypeface(Typeface.DEFAULT_BOLD);
        emergencyStopButton.setBackground(createStopButtonBackground());
        emergencyStopButton.setOnClickListener(v -> emergencyStop());
        stopActions.addView(emergencyStopButton, weightWrap(1f));

        nightModeButton = actionButton(nightModeEnabled ? R.string.night_mode_off : R.string.night_mode_on);
        nightModeButton.setOnClickListener(v -> toggleNightMode());
        nightModeButton.setTextColor(Color.WHITE);
        nightModeButton.setBackground(createNightModeButtonBackground());
        stopActions.addView(nightModeButton, weightWrapWithLeftMargin(1f, 8));
        panel.addView(stopActions, matchWrap());

        LinearLayout homeActions = new LinearLayout(this);
        homeActions.setOrientation(LinearLayout.HORIZONTAL);
        homeActions.setGravity(Gravity.CENTER_VERTICAL);
        homeActions.setPadding(0, dp(6), 0, 0);

        setHomeButton = actionButton(R.string.home_set_current);
        setHomeButton.setOnClickListener(v -> confirmSetHomeFromMountNative());
        homeActions.addView(setHomeButton, weightWrap(1f));

        gotoHomeButton = actionButton(R.string.home_goto);
        gotoHomeButton.setOnClickListener(v -> confirmReturnHomeNative());
        homeActions.addView(gotoHomeButton, weightWrapWithLeftMargin(1f, 8));
        panel.addView(homeActions, matchWrap());

        LinearLayout gotoActions = new LinearLayout(this);
        gotoActions.setOrientation(LinearLayout.HORIZONTAL);
        gotoActions.setGravity(Gravity.CENTER_VERTICAL);
        gotoActions.setPadding(0, dp(6), 0, 0);

        safetyCancelGotoButton = actionButton(R.string.goto_cancel);
        safetyCancelGotoButton.setOnClickListener(v -> cancelGoto());
        gotoActions.addView(safetyCancelGotoButton, weightWrap(1f));

        gotoStatusRefreshButton = actionButton(R.string.goto_refresh_status);
        gotoStatusRefreshButton.setOnClickListener(v -> refreshGotoStatus());
        gotoActions.addView(gotoStatusRefreshButton, weightWrapWithLeftMargin(1f, 8));
        panel.addView(gotoActions, matchWrap());

        LinearLayout parkActions = new LinearLayout(this);
        parkActions.setOrientation(LinearLayout.HORIZONTAL);
        parkActions.setGravity(Gravity.CENTER_VERTICAL);
        parkActions.setPadding(0, dp(6), 0, 0);

        parkButton = actionButton(R.string.park_mount);
        parkButton.setOnClickListener(v -> parkMount());
        parkActions.addView(parkButton, weightWrap(1f));

        unparkButton = actionButton(R.string.unpark_mount);
        unparkButton.setOnClickListener(v -> unparkMount());
        parkActions.addView(unparkButton, weightWrapWithLeftMargin(1f, 8));
        panel.addView(parkActions, matchWrap());

        safetyStatusText = bodyText(R.string.safety_status_idle);
        safetyStatusText.setPadding(0, dp(8), 0, 0);
        if (safetyStatusMessage != null) {
            safetyStatusText.setText(safetyStatusMessage);
        }
        panel.addView(safetyStatusText, matchWrap());

        return panel;
    }

    private View createCommandLogPanel() {
        LinearLayout panel = card();

        logScrollView = new ScrollView(this);
        logScrollView.setFillViewport(false);
        logText = bodyText(R.string.log_empty);
        logText.setTextColor(bodyTextColor());
        logText.setMinLines(8);
        logText.setGravity(Gravity.START);
        logText.setTextIsSelectable(true);
        logScrollView.addView(logText, matchWrap());
        panel.addView(logScrollView, matchFixedHeight(180));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button copyButton = actionButton(R.string.log_copy_recent);
        copyButton.setOnClickListener(v -> copyRecentLogLines());
        actions.addView(copyButton, weightWrap(1f));

        Button exportButton = actionButton(R.string.log_export);
        exportButton.setOnClickListener(v -> requestLogExport());
        actions.addView(exportButton, weightWrapWithLeftMargin(1f, 6));

        Button clearButton = actionButton(R.string.log_clear);
        clearButton.setOnClickListener(v -> confirmClearLog());
        actions.addView(clearButton, weightWrapWithLeftMargin(1f, 6));

        panel.addView(actions, matchWrap());
        updateLogText();
        return panel;
    }

    private void copyRecentLogLines() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            setStatus(getString(R.string.log_copy_failed));
            return;
        }
        String text = Logger.recentText(100);
        if (text.isEmpty()) {
            text = getString(R.string.log_empty);
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.command_log_section), text));
        Logger.user("copy recent log lines");
        setStatus(getString(R.string.log_copied));
    }

    private void requestLogExport() {
        long acceptedAt = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getLong(PREF_LOG_PRIVACY_ACCEPTED_AT, 0L);
        if (System.currentTimeMillis() - acceptedAt < LOG_PRIVACY_ACK_VALID_MS) {
            shareLog();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.log_privacy_title)
                .setMessage(R.string.log_privacy_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.log_privacy_continue, (dialog, which) -> {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putLong(PREF_LOG_PRIVACY_ACCEPTED_AT, System.currentTimeMillis())
                            .apply();
                    shareLog();
                })
                .show();
    }

    private void shareLog() {
        try {
            Logger.user("export log");
            if (!Logger.flushForExport()) {
                Logger.warn("export log flush incomplete");
            }
            Intent share = LogExporter.createShareIntent(this);
            startActivity(Intent.createChooser(share, getString(R.string.log_export_chooser)));
        } catch (Exception ex) {
            Logger.error("export log failed", ex);
            setStatus(getString(R.string.log_export_failed, safeMessage(ex)));
        }
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.log_clear_confirm, (dialog, which) -> {
                    Logger.user("clear today log");
                    Logger.clearToday();
                    setStatus(getString(R.string.log_cleared));
                    updateLogText();
                })
                .show();
    }

    private View createSmallBodiesPanel() {
        LinearLayout panel = card();

        smallBodyStatusText = bodyText(R.string.app_name);
        smallBodyStatusText.setPadding(0, 0, 0, dp(6));
        panel.addView(smallBodyStatusText, matchWrap());

        TextView magLabel = bodyText(R.string.small_bodies_mag_limit_label);
        magLabel.setPadding(0, dp(6), 0, 0);
        panel.addView(magLabel, matchWrap());

        SeekBar magSlider = new SeekBar(this);
        magSlider.setMax(100);
        double initialMag = smallBodyCatalog == null ? 11.0 : smallBodyCatalog.magnitudeLimit();
        magSlider.setProgress((int) Math.round((initialMag - 5.0) * 10.0));
        magLabel.setText(getString(R.string.small_bodies_mag_limit_value, initialMag));
        magSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double mag = 5.0 + progress / 10.0;
                if (smallBodyCatalog != null) {
                    smallBodyCatalog.setMagnitudeLimit(mag);
                }
                magLabel.setText(getString(R.string.small_bodies_mag_limit_value, mag));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Logger.info("small-body magnitude limit changed value="
                        + (smallBodyCatalog == null ? "unknown" : smallBodyCatalog.magnitudeLimit()));
                if (skyChartView != null) {
                    skyChartView.invalidate();
                }
            }
        });
        panel.addView(magSlider, matchWrap());

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        buttonRow.setPadding(0, dp(6), 0, 0);

        smallBodyDownloadAsteroidsButton = actionButton(R.string.small_bodies_download_asteroids);
        smallBodyDownloadAsteroidsButton.setOnClickListener(v -> startAsteroidDownload());
        buttonRow.addView(smallBodyDownloadAsteroidsButton, weightWrap(1f));

        smallBodyDownloadCometsButton = actionButton(R.string.small_bodies_add_comet_button);
        smallBodyDownloadCometsButton.setOnClickListener(v -> showAddCometDialog());
        buttonRow.addView(smallBodyDownloadCometsButton, weightWrapWithLeftMargin(1f, 8));
        panel.addView(buttonRow, matchWrap());

        smallBodyClearUserButton = actionButton(R.string.small_bodies_clear_user);
        smallBodyClearUserButton.setOnClickListener(v -> clearUserSmallBodies());
        LinearLayout.LayoutParams clearParams = matchWrap();
        clearParams.topMargin = dp(8);
        panel.addView(smallBodyClearUserButton, clearParams);

        updateSmallBodyStatusText();
        return panel;
    }

    private void updateSmallBodyStatusText() {
        if (smallBodyStatusText == null || smallBodyCatalog == null) {
            return;
        }
        smallBodyStatusText.setText(getString(
                R.string.small_bodies_status,
                smallBodyCatalog.bundledCount(),
                smallBodyCatalog.userAsteroidCount(),
                smallBodyCatalog.userCometCount()
        ));
    }

    private void startAsteroidDownload() {
        if (smallBodyCatalog == null || smallBodyDownloadAsteroidsButton == null) {
            return;
        }
        final double maxH = smallBodyCatalog.magnitudeLimit();
        logUserAction("tap download-asteroids maxH=" + String.format(Locale.US, "%.1f", maxH));
        smallBodyDownloadAsteroidsButton.setEnabled(false);
        setStatus(getString(R.string.small_bodies_download_starting));
        ioExecutor.execute(() -> {
            try {
                String constraint = "{\"AND\":[\"H|LE|" + ((int) Math.round(maxH)) + "\"]}";
                String url = "https://ssd-api.jpl.nasa.gov/sbdb_query.api"
                        + "?fields=full_name,e,i,om,w,q,tp,H,G"
                        + "&sb-kind=a"
                        + "&sb-cdata=" + java.net.URLEncoder.encode(constraint, StandardCharsets.UTF_8.name())
                        + "&full-prec=true";
                String json = httpGet(url);
                List<SmallBody> parsed = parseSbdbJson(json, false);
                smallBodyCatalog.replaceUserAsteroids(parsed);
                runOnUiThread(() -> {
                    setStatus(getString(R.string.small_bodies_download_done, parsed.size()));
                    Logger.info("small-body asteroid download success maxH=" + maxH + " count=" + parsed.size());
                    smallBodyDownloadAsteroidsButton.setEnabled(true);
                    updateSmallBodyStatusText();
                    if (skyChartView != null) {
                        skyChartView.invalidate();
                    }
                });
            } catch (Throwable t) {
                Logger.error("small-body asteroid download failed maxH=" + maxH, t);
                runOnUiThread(() -> {
                    setStatus(getString(R.string.small_bodies_download_failed,
                            t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                    smallBodyDownloadAsteroidsButton.setEnabled(true);
                });
            }
        });
    }

    private void showAddCometDialog() {
        if (smallBodyCatalog == null) {
            return;
        }
        logUserAction("tap add-comet-dialog");
        EditText input = compactEditText();
        input.setHint(R.string.small_bodies_add_comet_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        FrameLayout wrapper = new FrameLayout(this);
        int padding = dp(16);
        wrapper.setPadding(padding, dp(8), padding, 0);
        wrapper.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.small_bodies_add_comet_title)
                .setMessage(R.string.small_bodies_add_comet_message)
                .setView(wrapper)
                .setPositiveButton(R.string.small_bodies_add_comet_ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            // Override the positive button's click so we can validate input
            // and only dismiss the dialog after a non-empty name is submitted.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (name.isEmpty()) {
                    input.setError(getString(R.string.small_bodies_add_comet_empty));
                    input.requestFocus();
                    return;
                }
                dialog.dismiss();
                logUserAction("submit add-comet query=\"" + name + "\"");
                fetchSingleSmallBody(name, true);
            });
        });
        dialog.show();
    }

    private void fetchSingleSmallBody(String query, boolean isComet) {
        if (smallBodyCatalog == null) {
            return;
        }
        if (smallBodyDownloadCometsButton != null) {
            smallBodyDownloadCometsButton.setEnabled(false);
        }
        Logger.info("small-body single fetch start query=\"" + query + "\" comet=" + isComet);
        setStatus(getString(R.string.small_bodies_download_starting));
        ioExecutor.execute(() -> {
            try {
                String json = fetchSbdbResolvingMultiMatch(query, isComet);
                SmallBody body = parseSbdbSingleRecord(json, isComet);
                if (body == null) {
                    runOnUiThread(() -> {
                        setStatus(getString(R.string.small_bodies_add_not_found, query));
                        Logger.warn("small-body single fetch not found query=\"" + query + "\" comet=" + isComet);
                        if (smallBodyDownloadCometsButton != null) {
                            smallBodyDownloadCometsButton.setEnabled(true);
                        }
                    });
                    return;
                }
                smallBodyCatalog.addUserBody(body);
                runOnUiThread(() -> {
                    setStatus(getString(R.string.small_bodies_add_done, body.displayLabel()));
                    Logger.info("small-body single fetch success query=\"" + query
                            + "\" comet=" + isComet
                            + " body=\"" + body.displayLabel() + "\"");
                    if (smallBodyDownloadCometsButton != null) {
                        smallBodyDownloadCometsButton.setEnabled(true);
                    }
                    updateSmallBodyStatusText();
                    if (skyChartView != null) {
                        skyChartView.invalidate();
                    }
                });
            } catch (Throwable t) {
                Logger.error("small-body single fetch failed query=\"" + query + "\" comet=" + isComet, t);
                runOnUiThread(() -> {
                    setStatus(getString(R.string.small_bodies_download_failed,
                            t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                    if (smallBodyDownloadCometsButton != null) {
                        smallBodyDownloadCometsButton.setEnabled(true);
                    }
                });
            }
        });
    }

    /**
     * Parse a single-body SBDB API response (sbdb.api?sstr=...). The single-body
     * endpoint returns object/orbit/phys_par as nested objects with named
     * elements, not the positional rows that the bulk Query API uses, so we
     * cannot share the parser.
     */
    private static SmallBody parseSbdbSingleRecord(String json, boolean isComet) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject object = root.optJSONObject("object");
        JSONObject orbit = root.optJSONObject("orbit");
        if (object == null || orbit == null) {
            return null;
        }
        // Defense-in-depth: SBDB sets object.kind to "an"/"au" for asteroids
        // and "cn"/"cu" for comets (numbered/unnumbered). Reject responses
        // whose kind disagrees with what the caller asked for, so a stray
        // asteroid never lands in the comet pool even if the multi-match
        // filter let it slip.
        String kind = object.optString("kind", "").toLowerCase(Locale.US);
        if (!kind.isEmpty()) {
            boolean responseIsComet = kind.startsWith("c");
            if (responseIsComet != isComet) {
                return null;
            }
        }
        // Build the canonical IAU designation. JPL stores it split between
        // object.des (the body part) and object.prefix (the leading IAU letter)
        // for non-numbered comets:
        //   periodic comet "1P/Halley":      des="1P",       prefix=""    -> "1P"
        //   non-numbered comet "C/2023 A3":  des="2023 A3",  prefix="C"   -> "C/2023 A3"
        //   asteroid "1 Ceres":              des="1",        prefix=""    -> "1"
        // Earlier versions used the slash-truncating extractDesignation(fullname)
        // which collapsed every C/... comet into a single "C" bucket; using the
        // structured object.* fields avoids that.
        String desRaw = object.optString("des", "").trim();
        if (desRaw.isEmpty()) {
            return null;
        }
        String prefix = object.optString("prefix", "").trim();
        String designation;
        if (isComet && !desRaw.contains("/") && !prefix.isEmpty()
                && desRaw.matches("\\d{4}.*")) {
            designation = prefix + "/" + desRaw;
        } else {
            designation = desRaw;
        }
        String fullName = object.optString("fullname", designation).trim();
        String displayName = extractDisplayName(fullName);

        JSONArray elements = orbit.optJSONArray("elements");
        if (elements == null) {
            return null;
        }
        double e = sbdbElementValue(elements, "e", Double.NaN);
        double a = sbdbElementValue(elements, "a", Double.NaN);
        double q = sbdbElementValue(elements, "q", Double.NaN);
        double inc = sbdbElementValue(elements, "i", Double.NaN);
        double om = sbdbElementValue(elements, "om", Double.NaN);
        double w = sbdbElementValue(elements, "w", Double.NaN);
        double tp = sbdbElementValue(elements, "tp", Double.NaN);
        double epoch = parseDouble(orbit.optString("epoch", ""), Double.NaN);
        if (!Double.isFinite(q) && Double.isFinite(a) && Double.isFinite(e) && e < 1.0) {
            q = a * (1.0 - e);
        }
        if (!Double.isFinite(e) || !Double.isFinite(q) || !Double.isFinite(tp)
                || q <= 0.0 || e < 0.0 || e > 4.0) {
            return null;
        }

        JSONArray physPar = root.optJSONArray("phys_par");
        double absMag;
        double slope;
        if (isComet) {
            absMag = sbdbPhysParValue(physPar, "M1", sbdbPhysParValue(physPar, "M2", 12.0));
            slope = sbdbPhysParValue(physPar, "K1", sbdbPhysParValue(physPar, "K2", 8.0));
        } else {
            absMag = sbdbPhysParValue(physPar, "H", 14.0);
            slope = sbdbPhysParValue(physPar, "G", 0.15);
        }
        return new SmallBody(designation, "", displayName, isComet,
                Double.isFinite(epoch) ? epoch : tp,
                q, e, inc, om, w, tp, absMag, slope);
    }

    private static double sbdbElementValue(JSONArray elements, String name, double fallback) {
        if (elements == null) {
            return fallback;
        }
        for (int i = 0; i < elements.length(); i++) {
            JSONObject entry = elements.optJSONObject(i);
            if (entry != null && name.equals(entry.optString("name"))) {
                return parseDouble(entry.optString("value", ""), fallback);
            }
        }
        return fallback;
    }

    private static double sbdbPhysParValue(JSONArray physPar, String name, double fallback) {
        if (physPar == null) {
            return fallback;
        }
        for (int i = 0; i < physPar.length(); i++) {
            JSONObject entry = physPar.optJSONObject(i);
            if (entry != null && name.equals(entry.optString("name"))) {
                return parseDouble(entry.optString("value", ""), fallback);
            }
        }
        return fallback;
    }

    private void clearUserSmallBodies() {
        if (smallBodyCatalog == null) {
            return;
        }
        logUserAction("tap clear-user-small-bodies");
        try {
            smallBodyCatalog.clearUserBodies();
            updateSmallBodyStatusText();
            if (skyChartView != null) {
                skyChartView.invalidate();
            }
            setStatus(getString(R.string.small_bodies_cleared));
            Logger.info("small-body user catalog cleared");
        } catch (IOException ex) {
            Logger.error("small-body clear failed", ex);
            setStatus(getString(R.string.small_bodies_clear_failed, safeMessage(ex)));
        }
    }

    private void showLayerDialog() {
        if (skyChartView == null) {
            return;
        }
        logUserAction("open sky-layer-dialog");
        SkyChartView.LayerType[] layers = new SkyChartView.LayerType[]{
                SkyChartView.LayerType.CONSTELLATION_LINES,
                SkyChartView.LayerType.SOLAR_SYSTEM,
                SkyChartView.LayerType.CLUSTERS,
                SkyChartView.LayerType.NEBULAE,
                SkyChartView.LayerType.GALAXIES,
                SkyChartView.LayerType.ASTEROIDS,
                SkyChartView.LayerType.COMETS
        };
        String[] labels = new String[]{
                getString(R.string.layer_constellation_lines),
                getString(R.string.layer_solar_system),
                getString(R.string.layer_clusters),
                getString(R.string.layer_nebulae),
                getString(R.string.layer_galaxies),
                getString(R.string.layer_asteroids),
                getString(R.string.layer_comets)
        };
        boolean[] checked = new boolean[layers.length];
        for (int i = 0; i < layers.length; i++) {
            checked[i] = skyChartView.isLayerVisible(layers[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sky_layers_dialog_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                {
                    skyChartView.setLayerVisible(layers[which], isChecked);
                    Logger.info("sky layer changed layer=" + layers[which].name() + " visible=" + isChecked);
                })
                .setPositiveButton(R.string.layer_dialog_done, null)
                .show();
    }

    private static String httpGet(String urlStr) throws IOException {
        return httpGetAcceptingCodes(urlStr, false);
    }

    /**
     * GET that returns the body for both 200 and 300 (Multiple Choices). JPL
     * SBDB returns 300 with a JSON {@code "list"} of candidate matches when
     * the input string is ambiguous (e.g. "Halley" matches multiple bodies).
     */
    private static String httpGetAllowMultiChoice(String urlStr) throws IOException {
        return httpGetAcceptingCodes(urlStr, true);
    }

    private static String httpGetAcceptingCodes(String urlStr, boolean allow300) throws IOException {
        try {
            return httpGetAcceptingCodesInternal(urlStr, allow300, false);
        } catch (IOException firstFailure) {
            if (isJplSbdbUrl(urlStr) && isCertificateTrustFailure(firstFailure)) {
                Logger.warn("small-body http TLS trust fallback url=" + urlStr
                        + " reason=" + firstFailure.getClass().getSimpleName()
                        + " " + safeMessage(firstFailure));
                return httpGetAcceptingCodesInternal(urlStr, allow300, true);
            }
            Logger.error("small-body http failed url=" + urlStr, firstFailure);
            throw firstFailure;
        }
    }

    private static String httpGetAcceptingCodesInternal(String urlStr, boolean allow300, boolean relaxedJplTls)
            throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (relaxedJplTls && conn instanceof HttpsURLConnection) {
            configureJplTlsFallback((HttpsURLConnection) conn, url);
        }
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MountBehave-Android");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            Logger.info("small-body http response code=" + code
                    + " allow300=" + allow300
                    + " relaxedTls=" + relaxedJplTls
                    + " url=" + urlStr);
            boolean ok = code == 200 || (allow300 && code == 300);
            if (!ok) {
                Logger.warn("small-body http unexpected-status code=" + code + " url=" + urlStr);
                throw new IOException("HTTP " + code);
            }
            java.io.InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) {
                throw new IOException("HTTP " + code + " (empty body)");
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isJplSbdbUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && JPL_SBDB_HOST.equalsIgnoreCase(url.getHost())
                    && url.getPath() != null
                    && url.getPath().endsWith("/sbdb.api");
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean isCertificateTrustFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            String className = cursor.getClass().getName();
            String message = cursor.getMessage();
            if (cursor instanceof SSLHandshakeException
                    || className.contains("CertPathValidatorException")
                    || className.contains("CertificateException")
                    || (message != null && message.contains("Trust anchor for certification path not found"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static void configureJplTlsFallback(HttpsURLConnection conn, URL url) throws IOException {
        if (!JPL_SBDB_HOST.equalsIgnoreCase(url.getHost())) {
            throw new IOException("Refusing relaxed TLS for " + url.getHost());
        }
        conn.setSSLSocketFactory(relaxedJplSslContext().getSocketFactory());
    }

    private static SSLContext relaxedJplSslContext() throws IOException {
        SSLContext cached = relaxedJplSslContext;
        if (cached != null) {
            return cached;
        }
        synchronized (MainActivity.class) {
            cached = relaxedJplSslContext;
            if (cached != null) {
                return cached;
            }
            try {
                TrustManager[] trustManagers = new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustManagers, new SecureRandom());
                relaxedJplSslContext = context;
                return context;
            } catch (Exception ex) {
                throw new IOException("JPL TLS fallback unavailable", ex);
            }
        }
    }

    /**
     * Resolve the user-typed query against the SBDB single-body endpoint.
     * Strategy:
     * <ol>
     *  <li>If the query looks like a designation, try {@code des=} first.
     *  <li>Otherwise try {@code sstr=}.
     *  <li>On HTTP 300 (JPL's "multiple matches" envelope), filter the
     *      {@code list} by {@code wantComet} since names like {@code Halley}
     *      and {@code Encke} match BOTH a same-named asteroid (2688 Halley,
     *      9134 Encke) AND the comet — JPL returns the asteroid first.
     * </ol>
     */
    private static String fetchSbdbResolvingMultiMatch(String query, boolean wantComet) throws IOException, JSONException {
        String trimmed = query.trim();
        // Many designations contain a slash (e.g. "1P/Halley"); strip the name suffix
        // for the des= retry path.
        String stripped = trimmed;
        int slash = stripped.indexOf('/');
        if (slash > 0 && stripped.indexOf(' ') < 0) {
            stripped = stripped.substring(0, slash);
        }
        boolean looksLikeDesignation = stripped.matches("(?i)^[CPDXAI]?/?\\s*\\d.*");
        String firstUrl = sbdbUrl(looksLikeDesignation ? "des" : "sstr", looksLikeDesignation ? stripped : trimmed);
        String json = httpGetAllowMultiChoice(firstUrl);
        JSONObject root = new JSONObject(json);
        if ("300".equals(root.optString("code"))) {
            JSONArray list = root.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject candidate = list.getJSONObject(i);
                    String pdes = candidate.optString("pdes", "").trim();
                    if (pdes.isEmpty()) {
                        continue;
                    }
                    if (candidateLooksLikeComet(candidate) == wantComet) {
                        return httpGet(sbdbUrl("des", pdes));
                    }
                }
            }
            throw new IOException("Multiple matches but none of the requested kind");
        }
        return json;
    }

    /**
     * Whether a 300-envelope candidate refers to a comet. We must check both
     * fields because JPL returns non-numbered comets with bare year-format
     * {@code pdes} like {@code 2023 A3} (no prefix) and only the comet
     * provenance shows up in {@code name} like {@code C/2023 A3 (Tsuchinshan-ATLAS)}.
     */
    private static boolean candidateLooksLikeComet(JSONObject candidate) {
        String pdes = candidate.optString("pdes", "");
        String name = candidate.optString("name", "");
        return pdesLooksLikeComet(pdes) || nameLooksLikeComet(name);
    }

    /**
     * Comet primary-designation patterns: {@code N[PDXAI]} (numbered periodic)
     * or {@code [CPDXAI]/...} (IAU-format with prefix already present).
     */
    private static boolean pdesLooksLikeComet(String pdes) {
        if (pdes == null || pdes.isEmpty()) {
            return false;
        }
        return pdes.matches("\\d+[PDXAIpdxai]") || pdes.matches("[CPDXAIcpdxai]/.*");
    }

    /**
     * Detect comet from JPL display name: anything beginning with one of the
     * IAU comet prefix letters followed by a slash, e.g. {@code C/2023 A3 (...)}.
     */
    private static boolean nameLooksLikeComet(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.matches("(?i)^[CPDXAI]/.*");
    }

    private static String sbdbUrl(String key, String value) {
        try {
            return "https://ssd-api.jpl.nasa.gov/sbdb.api?"
                    + key + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    + "&full-prec=true&phys-par=true";
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<SmallBody> parseSbdbJson(String json, boolean isComet) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray data = root.optJSONArray("data");
        if (data == null) {
            return new ArrayList<>();
        }
        // Field order is fixed by our query: full_name,e,i,om,w,q,tp,H/M1,G/K1
        List<SmallBody> out = new ArrayList<>(data.length());
        for (int i = 0; i < data.length(); i++) {
            JSONArray row = data.getJSONArray(i);
            String fullName = row.optString(0, "").trim();
            String designation = extractDesignation(fullName, isComet);
            String displayName = extractDisplayName(fullName);
            double e = parseDouble(row.optString(1, ""), Double.NaN);
            double inc = parseDouble(row.optString(2, ""), Double.NaN);
            double om = parseDouble(row.optString(3, ""), Double.NaN);
            double w = parseDouble(row.optString(4, ""), Double.NaN);
            double q = parseDouble(row.optString(5, ""), Double.NaN);
            double tp = parseDouble(row.optString(6, ""), Double.NaN);
            double h = parseDouble(row.optString(7, ""), Double.NaN);
            double slope = parseDouble(row.optString(8, ""), isComet ? 4.0 : 0.15);
            if (!Double.isFinite(e) || !Double.isFinite(q) || !Double.isFinite(tp)
                    || q <= 0.0 || e < 0.0 || e > 4.0) {
                continue;
            }
            if (!Double.isFinite(h)) {
                h = isComet ? 12.0 : 14.0;
            }
            out.add(new SmallBody(designation, "", displayName, isComet, tp, q, e, inc, om, w, tp, h, slope));
        }
        return out;
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String extractDesignation(String fullName, boolean isComet) {
        String trimmed = fullName.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (isComet) {
            int slash = trimmed.indexOf('/');
            if (slash > 0) {
                return trimmed.substring(0, slash).trim();
            }
            int space = trimmed.indexOf(' ');
            return space > 0 ? trimmed.substring(0, space).trim() : trimmed;
        }
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space).trim() : trimmed;
    }

    private static String extractDisplayName(String fullName) {
        String trimmed = fullName.trim();
        int paren = trimmed.indexOf('(');
        if (paren > 0) {
            trimmed = trimmed.substring(0, paren).trim();
        }
        return trimmed;
    }

    private View createTrackingPanel() {
        LinearLayout panel = card();

        panel.addView(labelText(R.string.tracking_rate_label), matchWrap());

        LinearLayout rateActions = new LinearLayout(this);
        rateActions.setOrientation(LinearLayout.HORIZONTAL);
        rateActions.setGravity(Gravity.CENTER_VERTICAL);
        rateActions.setPadding(0, dp(6), 0, dp(8));

        trackingSiderealButton = trackingRateButton(TrackingRate.SIDEREAL);
        rateActions.addView(trackingSiderealButton, weightWrap(1f));

        trackingLunarButton = trackingRateButton(TrackingRate.LUNAR);
        rateActions.addView(trackingLunarButton, weightWrapWithLeftMargin(1f, 8));

        trackingSolarButton = trackingRateButton(TrackingRate.SOLAR);
        rateActions.addView(trackingSolarButton, weightWrapWithLeftMargin(1f, 8));

        panel.addView(rateActions, matchWrap());

        trackingToggleButton = actionButton(R.string.tracking_start);
        trackingToggleButton.setOnClickListener(v -> toggleTracking());
        panel.addView(trackingToggleButton, matchWrap());

        trackingStatusText = bodyText(R.string.tracking_status_off);
        trackingStatusText.setPadding(0, dp(8), 0, 0);
        panel.addView(trackingStatusText, matchWrap());

        updateTrackingViews();
        return panel;
    }

    private View createObserverPanel() {
        LinearLayout panel = card();

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout latitudeBox = new LinearLayout(this);
        latitudeBox.setOrientation(LinearLayout.VERTICAL);
        latitudeBox.addView(labelText(R.string.latitude_label), matchWrap());
        latitudeField = coordinateField(ObserverState.BOSTON_LATITUDE);
        latitudeBox.addView(latitudeField, matchWrap());
        fields.addView(latitudeBox, weightWrap(1f));

        LinearLayout longitudeBox = new LinearLayout(this);
        longitudeBox.setOrientation(LinearLayout.VERTICAL);
        longitudeBox.addView(labelText(R.string.longitude_label), matchWrap());
        longitudeField = coordinateField(ObserverState.BOSTON_LONGITUDE);
        longitudeBox.addView(longitudeField, matchWrap());
        fields.addView(longitudeBox, weightWrapWithLeftMargin(1f, 8));

        panel.addView(fields, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button gpsButton = new Button(this);
        gpsButton.setAllCaps(false);
        compactButton(gpsButton);
        gpsButton.setText(R.string.use_gps);
        gpsButton.setOnClickListener(v -> requestGpsLocation());
        actions.addView(gpsButton, weightWrap(1f));

        Button applyButton = new Button(this);
        applyButton.setAllCaps(false);
        compactButton(applyButton);
        applyButton.setText(R.string.apply_location);
        applyButton.setOnClickListener(v -> applyManualLocation());
        actions.addView(applyButton, weightWrapWithLeftMargin(1f, 8));

        panel.addView(actions, matchWrap());

        syncMountButton = new Button(this);
        syncMountButton.setAllCaps(false);
        compactButton(syncMountButton);
        syncMountButton.setText(R.string.sync_observer_to_mount);
        syncMountButton.setOnClickListener(v -> syncObserverToMount());
        LinearLayout.LayoutParams syncParams = matchWrapWithTopMargin(8);
        panel.addView(syncMountButton, syncParams);

        observerStatusText = bodyText(R.string.sky_loading);
        observerStatusText.setPadding(0, dp(8), 0, 0);
        panel.addView(observerStatusText, matchWrap());

        timeStatusText = bodyText(R.string.sky_loading);
        timeStatusText.setPadding(0, dp(3), 0, 0);
        panel.addView(timeStatusText, matchWrap());

        return panel;
    }

    private View createConnectionPanel() {
        LinearLayout panel = card();
        panel.addView(createMountProfileBadge(), matchWrap());

        connectionForm = new LinearLayout(this);
        connectionForm.setOrientation(LinearLayout.VERTICAL);

        LinearLayout endpointRow = new LinearLayout(this);
        endpointRow.setOrientation(LinearLayout.HORIZONTAL);
        endpointRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout hostBox = new LinearLayout(this);
        hostBox.setOrientation(LinearLayout.VERTICAL);
        hostBox.addView(labelText(R.string.host_label), matchWrap());
        hostField = compactEditText();
        hostField.setText(R.string.default_onstep_host);
        hostField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostBox.addView(hostField, matchWrap());
        endpointRow.addView(hostBox, weightWrap(1f));

        LinearLayout portBox = new LinearLayout(this);
        portBox.setOrientation(LinearLayout.VERTICAL);
        portBox.addView(labelText(R.string.port_label), matchWrap());
        portField = compactEditText();
        portField.setText(String.format(Locale.US, "%d", DEFAULT_PORT));
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portBox.addView(portField, new LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams portBoxParams = new LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT);
        portBoxParams.leftMargin = dp(8);
        endpointRow.addView(portBox, portBoxParams);

        connectionForm.addView(endpointRow, matchWrap());
        panel.addView(connectionForm, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(6), 0, 0);

        disconnectButton = new Button(this);
        disconnectButton.setAllCaps(false);
        compactButton(disconnectButton);
        disconnectButton.setText(R.string.disconnect_button);
        disconnectButton.setOnClickListener(v -> disconnect());
        actions.addView(disconnectButton, matchWrap());

        panel.addView(actions, matchWrap());

        statusText = bodyText(R.string.status_disconnected);
        statusText.setPadding(0, dp(8), 0, 0);
        if (currentStatusMessage != null) {
            statusText.setText(currentStatusMessage);
        }
        panel.addView(statusText, matchWrap());

        return panel;
    }

    private View createMountProfileBadge() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(8));

        LinearLayout connectColumn = new LinearLayout(this);
        connectColumn.setOrientation(LinearLayout.VERTICAL);
        connectColumn.setGravity(Gravity.CENTER);
        connectColumn.setPadding(dp(4), dp(4), dp(4), dp(4));
        connectColumn.setContentDescription(getString(R.string.connect_button));
        connectColumn.setBackground(createConnectBadgeBackground(true));
        connectColumn.setClickable(true);
        connectColumn.setOnClickListener(v -> connect());
        connectTrigger = connectColumn;

        ImageView badge = new ImageView(this);
        badge.setImageResource(R.drawable.clearsky_badge);
        badge.setAdjustViewBounds(true);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.setPadding(dp(1), dp(1), dp(1), dp(1));
        badge.setContentDescription(getString(R.string.connect_button));
        connectColumn.addView(badge, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView connectChip = new TextView(this);
        connectChip.setText(R.string.connect_button);
        connectChip.setTextSize(12);
        connectChip.setTypeface(Typeface.DEFAULT_BOLD);
        connectChip.setGravity(Gravity.CENTER);
        connectChip.setTextColor(Color.WHITE);
        connectChip.setBackground(createConnectChipBackground(true));
        connectChip.setPadding(dp(8), dp(1), dp(8), dp(2));
        connectColumn.addView(connectChip, matchWrapWithTopMargin(3));

        row.addView(connectColumn, new LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        textColumn.addView(panelTitleWithHelp(R.string.mount_profile_title, R.string.mount_profile_body), matchWrap());

        row.addView(textColumn, weightWrapWithLeftMargin(1f, 10));
        return row;
    }

    private View createControlPanel() {
        LinearLayout panel = card();

        TextView rateLabel = labelText(R.string.rate_label);
        panel.addView(rateLabel, matchWrap());

        LinearLayout ratePanel = new LinearLayout(this);
        ratePanel.setOrientation(LinearLayout.VERTICAL);
        ratePanel.setPadding(0, dp(4), 0, dp(8));

        manualRateSpinner = new Spinner(this);
        List<String> rateLabels = new ArrayList<>();
        for (ManualRate rate : ManualRate.values()) {
            rateLabels.add(getString(rate.labelRes));
        }
        ArrayAdapter<String> rateAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                rateLabels
        );
        rateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        manualRateSpinner.setAdapter(rateAdapter);
        manualRateSpinner.setSelection(selectedManualRate.ordinal());
        manualRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ManualRate rate = ManualRate.values()[position];
                if (selectedManualRate == rate) {
                    return;
                }
                logUserAction("select manual-rate " + rate.name() + " connected=" + connected);
                selectedManualRate = rate;
                if (connected) {
                    enqueueCommand(selectedManualRate.command, getString(R.string.log_rate_changed));
                }
                logStateSnapshot("manual-rate-selected");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current rate.
            }
        });
        ratePanel.addView(manualRateSpinner, matchWrap());
        panel.addView(ratePanel, matchWrap());
        updateManualRateControl();

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);

        northButton = directionButton(R.string.direction_north, Direction.NORTH);
        northEastButton = directionButton(R.string.direction_north_east, Direction.NORTH_EAST);
        northWestButton = directionButton(R.string.direction_north_west, Direction.NORTH_WEST);
        southButton = directionButton(R.string.direction_south, Direction.SOUTH);
        southEastButton = directionButton(R.string.direction_south_east, Direction.SOUTH_EAST);
        southWestButton = directionButton(R.string.direction_south_west, Direction.SOUTH_WEST);
        eastButton = directionButton(R.string.direction_east, Direction.EAST);
        westButton = directionButton(R.string.direction_west, Direction.WEST);

        LinearLayout rowTop = centeredRow();
        rowTop.addView(northWestButton, controlButtonParams());
        rowTop.addView(northButton, controlButtonParams());
        rowTop.addView(northEastButton, controlButtonParams());
        grid.addView(rowTop, matchWrap());

        LinearLayout rowMiddle = centeredRow();
        rowMiddle.addView(westButton, controlButtonParams());
        stopButton = new Button(this);
        stopButton.setAllCaps(false);
        stopButton.setText(R.string.stop_button);
        stopButton.setTextColor(Color.WHITE);
        stopButton.setTextSize(17);
        stopButton.setTypeface(Typeface.DEFAULT_BOLD);
        stopButton.setMinHeight(dp(54));
        stopButton.setBackground(createStopButtonBackground());
        stopButton.setOnClickListener(v -> {
            logUserAction("tap manual-stop");
            activeDirection = null;
            enqueueStop(getString(R.string.log_stop_sent));
            logStateSnapshot("manual-stop");
        });
        rowMiddle.addView(stopButton, controlButtonParams());
        rowMiddle.addView(eastButton, controlButtonParams());
        grid.addView(rowMiddle, matchWrap());

        LinearLayout rowBottom = centeredRow();
        rowBottom.addView(southWestButton, controlButtonParams());
        rowBottom.addView(southButton, controlButtonParams());
        rowBottom.addView(southEastButton, controlButtonParams());
        grid.addView(rowBottom, matchWrap());

        panel.addView(grid, matchWrap());
        return panel;
    }

    private void connect() {
        String host = hostField.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().toString().trim());
        } catch (NumberFormatException ex) {
            Logger.warn("connect blocked: bad port input");
            setStatus(getString(R.string.status_bad_port));
            return;
        }

        if (host.isEmpty()) {
            Logger.warn("connect blocked: empty host");
            setStatus(getString(R.string.status_bad_host));
            return;
        }

        logUserAction("tap connect host=" + host + " port=" + port
                + " firmware=" + selectedFirmwareMode.name()
                + " mount=" + selectedMountMode.name());
        busy = true;
        setStatus(getString(R.string.status_connecting));
        updateUiState();
        logStateSnapshot("connect-start");
        appendLog("CONNECT " + host + ":" + port);

        ioExecutor.execute(() -> {
            try {
                SocketFactory socketFactory = stableWifiSocketFactory();
                ConnectionAttempt connection = connectToAvailablePort(host, port, socketFactory);
                runOnUiThread(() -> {
                    connected = true;
                    busy = false;
                    connectionGeneration.incrementAndGet();
                    connectedHost = host;
                    connectedPort = connection.port;
                    portField.setText(String.format(Locale.US, "%d", connection.port));
                    mountPointingFailureCount = 0;
                    mountPointingPollingPaused = true;
                    hasCurrentMountPosition = false;
                    hasAlignmentTrackingModel = false;
                    savedAlignmentStarCount = 0;
                    trackingEnabled = false;
                    trackingUsingDualAxis = false;
                    gotoInProgress = false;
                    homeFindInProgress = false;
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    parked = false;
                    activeDirection = null;
                    if (connection.handshake.isEmpty()) {
                        setStatus(getString(R.string.status_connected_no_reply_port, connection.port));
                        appendLog("RX <no handshake reply>");
                    } else {
                        setStatus(getString(R.string.status_connected_port, connection.port, connection.handshake));
                        appendLog("RX " + connection.handshake);
                    }
                    if (connection.port != port) {
                        appendLog("PORT " + connection.port);
                    }
                    setGotoStatus(getString(R.string.goto_status_idle));
                    setSafetyStatus(getString(R.string.safety_status_connected));
                    Logger.info("connect success host=" + host
                            + " requestedPort=" + port
                            + " connectedPort=" + connection.port
                            + " handshake=" + (connection.handshake.isEmpty() ? "<empty>" : connection.handshake));
                    logStateSnapshot("connect-success");
                    updateUiState();
                });
            } catch (IOException ex) {
                client.close();
                releaseWifiBinding();
                runOnUiThread(() -> {
                    connected = false;
                    busy = false;
                    connectionGeneration.incrementAndGet();
                    connectedHost = null;
                    connectedPort = DEFAULT_PORT;
                    mountPointingFailureCount = 0;
                    mountPointingPollingPaused = true;
                    activeDirection = null;
                    gotoInProgress = false;
                    homeFindInProgress = false;
                    parked = false;
                    setStatus(getString(R.string.status_connect_failed, ex.getMessage()));
                    setSafetyStatus(getString(R.string.safety_status_connect_failed));
                    Logger.error("connect failed host=" + host + " port=" + port, ex);
                    appendLog("ERROR " + safeMessage(ex));
                    logStateSnapshot("connect-failed");
                    updateUiState();
                });
            }
        });
    }

    private ConnectionAttempt connectToAvailablePort(String host, int requestedPort, SocketFactory socketFactory) throws IOException {
        IOException lastError = null;
        for (int candidatePort : connectionCandidatePorts(requestedPort)) {
            try {
                String handshake = client.connect(host, candidatePort, socketFactory);
                return new ConnectionAttempt(candidatePort, handshake);
            } catch (IOException ex) {
                lastError = ex;
            }
        }
        throw lastError == null ? new IOException("No OnStep port available") : lastError;
    }

    private int[] connectionCandidatePorts(int requestedPort) {
        int[] knownPorts = {9999, 9998, 9997, 9996};
        List<Integer> ports = new ArrayList<>();
        ports.add(requestedPort);
        for (int knownPort : knownPorts) {
            if (!ports.contains(knownPort)) {
                ports.add(knownPort);
            }
        }
        int[] result = new int[ports.size()];
        for (int i = 0; i < ports.size(); i++) {
            result[i] = ports.get(i);
        }
        return result;
    }

    private void disconnect() {
        logUserAction("tap disconnect host=" + (connectedHost == null ? "<none>" : connectedHost)
                + " port=" + connectedPort);
        busy = true;
        connectionGeneration.incrementAndGet();
        setStatus(getString(R.string.status_disconnecting));
        updateUiState();
        logStateSnapshot("disconnect-start");
        appendLog("DISCONNECT");

        ioExecutor.execute(() -> {
            try {
                if (client.isConnected()) {
                    client.sendNoReply(OnStepCommand.STOP_ALL.command);
                }
            } catch (IOException ignored) {
                // Closing anyway.
            } finally {
                client.close();
                releaseWifiBinding();
            }
            runOnUiThread(() -> {
                connected = false;
                busy = false;
                connectionGeneration.incrementAndGet();
                connectedHost = null;
                connectedPort = DEFAULT_PORT;
                mountPointingFailureCount = 0;
                mountPointingPollingPaused = false;
                hasCurrentMountPosition = false;
                hasAlignmentTrackingModel = false;
                savedAlignmentStarCount = 0;
                activeDirection = null;
                alignmentSession = null;
                gotoInProgress = false;
                homeFindInProgress = false;
                parked = false;
                trackingEnabled = false;
                trackingUsingDualAxis = false;
                clearQuickPointingCorrection();
                polarRefineSyncedTarget = null;
                setStatus(getString(R.string.status_disconnected));
                setGotoStatus(getString(R.string.goto_status_idle));
                setSafetyStatus(getString(R.string.safety_status_idle));
                appendLog("CLOSED");
                clearMountPointing();
                updateCalibrationViews();
                logStateSnapshot("disconnect-complete");
            });
        });
    }

    private void startMove(Direction direction) {
        if (!connected || activeDirection == direction) {
            return;
        }
        Direction previousDirection = activeDirection;
        clearSyncedCurrentTarget();
        activeDirection = direction;
        logUserAction("hold move direction=" + direction.name()
                + " rate=" + selectedManualRate.name()
                + " previous=" + (previousDirection == null ? "none" : previousDirection.name()));
        logStateSnapshot("move-start");
        if (previousDirection != null) {
            enqueueDirectionStop(previousDirection, getString(R.string.log_stop_sent));
        }
        String rate = getRateCommand();
        appendLog("TX " + rate);
        for (OnStepCommand command : direction.moveCommands) {
            appendLog("TX " + command.command);
        }
        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            try {
                client.sendNoReply(rate);
                for (OnStepCommand command : direction.moveCommands) {
                    client.sendNoReply(command.command);
                }
                runOnUiThread(() -> setStatus(getString(R.string.status_moving, direction.label(this))));
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> handleMotionCommandFailure(ex));
            }
        });
    }

    private void stopMove(Direction direction) {
        if (activeDirection != direction) {
            return;
        }
        activeDirection = null;
        logUserAction("release move direction=" + direction.name());
        logStateSnapshot("move-stop");
        enqueueDirectionStop(direction, getString(R.string.log_stop_sent));
    }

    private void enqueueStop(String logMessage) {
        enqueueCommands(new OnStepCommand[]{OnStepCommand.STOP_ALL}, logMessage);
    }

    private void enqueueImmediateStop(String logMessage) {
        if (!connected) {
            return;
        }
        appendLog("TX " + OnStepCommand.STOP_ALL.command);
        ioExecutor.execute(() -> {
            try {
                client.sendNoReply(OnStepCommand.STOP_ALL.command);
                runOnUiThread(() -> setStatus(logMessage));
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> handleMotionCommandFailure(ex));
            }
        });
    }

    private void emergencyStop() {
        logUserAction("tap emergency-stop");
        clearSyncedCurrentTarget();
        activeDirection = null;
        gotoInProgress = false;
        homeFindInProgress = false;
        setGotoStatus(getString(R.string.goto_status_cancelled));
        setSafetyStatus(getString(R.string.safety_status_emergency_stop));
        enqueueImmediateStop(getString(R.string.status_emergency_stop_sent));
        logStateSnapshot("emergency-stop");
        updateUiState();
    }

    private void cancelGoto() {
        logUserAction("tap cancel-goto");
        if (!connected) {
            setGotoStatus(getString(R.string.goto_status_not_connected));
            Logger.warn("cancel-goto blocked: not connected");
            return;
        }
        clearSyncedCurrentTarget();
        activeDirection = null;
        gotoInProgress = false;
        homeFindInProgress = false;
        setGotoStatus(getString(R.string.goto_status_cancelled));
        setSafetyStatus(getString(R.string.safety_status_goto_cancelled));
        enqueueImmediateStop(getString(R.string.goto_cancel_sent));
        logStateSnapshot("cancel-goto");
        updateUiState();
    }

    private void refreshGotoStatus() {
        if (!connected || busy) {
            Logger.info("goto-status refresh skipped connected=" + connected + " busy=" + busy);
            return;
        }
        Logger.info("goto-status refresh requested");
        busy = true;
        setGotoStatus(getString(R.string.goto_status_refreshing));
        updateUiState();
        logStateSnapshot("goto-status-refresh-start");
        appendLog("TX " + OnStepCommand.GOTO_STATUS.command);

        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            try {
                String reply = client.query(OnStepCommand.GOTO_STATUS.command);
                boolean moving = !reply.isEmpty();
                runOnUiThread(() -> {
                    busy = false;
                    gotoInProgress = moving;
                    appendLog("RX " + OnStepCommand.GOTO_STATUS.command + " -> " + (moving ? "moving" : "idle"));
                    setGotoStatus(getString(moving ? R.string.goto_status_running : R.string.goto_status_idle));
                    if (!moving) {
                        homeFindInProgress = false;
                    }
                    logStateSnapshot("goto-status-refresh-result moving=" + moving);
                    updateUiState();
                });
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
    }

    private void enqueueDirectionStop(Direction direction, String logMessage) {
        enqueueCommands(direction.stopCommands, logMessage);
    }

    private void enqueueCommand(String command, String logMessage) {
        enqueueRawCommands(new String[]{command}, logMessage, false);
    }

    private void enqueueCommands(OnStepCommand[] commands, String logMessage) {
        String[] rawCommands = new String[commands.length];
        for (int i = 0; i < commands.length; i++) {
            rawCommands[i] = commands[i].command;
        }
        enqueueRawCommands(rawCommands, logMessage, true);
    }

    private void enqueueRawCommands(String[] commands, String logMessage, boolean motionFailureShouldStop) {
        if (!connected) {
            return;
        }
        for (String command : commands) {
            appendLog("TX " + command);
        }
        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            try {
                for (String command : commands) {
                    client.sendNoReply(command);
                }
                runOnUiThread(() -> setStatus(logMessage));
            } catch (IOException ex) {
                markTransportFault();
                if (motionFailureShouldStop) {
                    runOnUiThread(() -> handleMotionCommandFailure(ex));
                } else {
                    runOnUiThread(() -> handleCommandFailure(ex));
                }
            }
        });
    }

    private void handleCommandFailure(IOException ex) {
        busy = false;
        activeDirection = null;
        setStatus(getString(R.string.status_command_failed_keep_connected, safeMessage(ex)));
        appendLog("WARN command " + safeMessage(ex));
        logStateSnapshot("command-failed");
        updateUiState();
        updateCalibrationViews();
    }

    private void handleMotionCommandFailure(IOException ex) {
        busy = false;
        activeDirection = null;
        setStatus(getString(R.string.status_motion_failed_keep_connected, safeMessage(ex)));
        appendLog("WARN motion " + safeMessage(ex));
        logStateSnapshot("motion-command-failed");
        updateUiState();
    }

    private void showTargetDialog() {
        EditText input = compactEditText();
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.target_input_hint);
        if (selectedSkyTarget != null) {
            input.setText(selectedSkyTarget.label);
            input.setSelection(input.getText().length());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.target_dialog_title)
                .setView(input)
                .setNegativeButton(R.string.cancel_button, null);
        if (connected) {
            builder.setPositiveButton(R.string.target_show_and_goto, null);
            builder.setNeutralButton(R.string.target_show_only, null);
        } else {
            builder.setPositiveButton(R.string.target_show_only, null);
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(openedDialog -> {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                SkyChartView.Target target = resolveTargetInput(input.getText().toString());
                if (target == null) {
                    Logger.warn("target dialog not found input=" + input.getText());
                    setStatus(getString(R.string.target_not_found));
                    return;
                }
                applySkyTarget(target, true);
                dialog.dismiss();
                if (connected) {
                    gotoTarget(target);
                }
            });
            if (connected) {
                dialog.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    SkyChartView.Target target = resolveTargetInput(input.getText().toString());
                    if (target == null) {
                        Logger.warn("target dialog show-only not found input=" + input.getText());
                        setStatus(getString(R.string.target_not_found));
                        return;
                    }
                    logUserAction("show target only " + targetLog(target));
                    applySkyTarget(target, true);
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private SkyChartView.Target resolveTargetInput(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) {
            return null;
        }
        SkyChartView.Target coordinateTarget = parseCoordinateTarget(input);
        if (coordinateTarget != null) {
            return coordinateTarget;
        }
        return skyChartView == null ? null : skyChartView.findTarget(input);
    }

    private SkyChartView.Target parseCoordinateTarget(String input) {
        Matcher matcher = COORDINATE_TARGET_PATTERN.matcher(input);
        if (!matcher.matches()) {
            return null;
        }
        double raHours;
        double decDegrees;
        try {
            raHours = parseRightAscension(matcher.group(1));
            decDegrees = parseDeclination(matcher.group(2));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String label = getString(
                R.string.custom_coordinate_target,
                formatRightAscensionDisplay(raHours),
                formatDeclinationDisplay(decDegrees)
        );
        return SkyChartView.Target.custom(label, raHours, decDegrees);
    }

    private void applySkyTarget(SkyChartView.Target target, boolean centerView) {
        selectSkyTarget(target, centerView);
        updatePageTabs(Page.SKY);
    }

    private void selectSkyTarget(SkyChartView.Target target, boolean centerView) {
        selectedSkyTarget = target;
        if (skyChartView != null) {
            skyChartView.setSelectedTarget(target, centerView);
        }
        updateTargetViews();
    }

    private void gotoTarget(SkyChartView.Target target) {
        logUserAction("tap sky-goto " + targetLog(target));
        sendGotoTarget(
                target,
                getString(R.string.status_goto_sending, target == null ? "" : target.label),
                getString(R.string.status_goto_sent, target == null ? "" : target.label),
                null
        );
    }

    private void sendGotoTarget(SkyChartView.Target target, String sendingStatus, String sentStatus, Runnable onSuccess) {
        if (!connected || busy || target == null) {
            return;
        }
        if (!quickPointingCorrectionActive && isSyncedCurrentTarget(target)) {
            gotoInProgress = false;
            homeFindInProgress = false;
            setStatus(getString(R.string.status_goto_already_synced, target.label));
            setGotoStatus(getString(R.string.goto_status_already_synced, target.label));
            appendLog("INFO GOTO skipped; " + target.label + " is the synced current target");
            logStateSnapshot("goto-skipped-synced " + targetLog(target));
            if (onSuccess != null) {
                onSuccess.run();
            }
            updateUiState();
            return;
        }
        clearSyncedCurrentTarget();
        homeFindInProgress = false;
        EquatorialPoint commandPoint = gotoCommandPoint(target);
        String raCommand = ":Sr" + formatRightAscensionCommand(commandPoint.raHours) + "#";
        String decCommand = ":Sd" + formatDeclinationCommand(commandPoint.decDegrees) + "#";
        busy = true;
        setStatus(sendingStatus);
        updateUiState();
        Logger.info("goto start " + targetLog(target)
                + " commandRA=" + formatRightAscensionDisplay(commandPoint.raHours)
                + " commandDec=" + formatDeclinationDisplay(commandPoint.decDegrees)
                + " quickCorrection=" + quickPointingCorrectionActive);
        logStateSnapshot("goto-start");
        if (quickPointingCorrectionActive) {
            appendLog("INFO quick sync correction RA "
                    + formatSignedDegrees(quickPointingRaOffsetHours * 15.0)
                    + " Dec " + formatSignedDegrees(quickPointingDecOffsetDegrees)
                    + " for " + target.label);
        }
        appendLog("TX " + raCommand);
        appendLog("TX " + decCommand);
        appendLog("TX :MS#");

        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            try {
                String raReply = client.query(raCommand);
                String decReply = client.query(decCommand);
                String gotoReply = client.query(":MS#");
                if ("0".equals(raReply)) {
                    throw new CommandRejectedException(raCommand, raReply);
                }
                if ("0".equals(decReply)) {
                    throw new CommandRejectedException(decCommand, decReply);
                }
                if (!"0".equals(gotoReply)) {
                    throw new CommandRejectedException(":MS#", describeGotoReply(gotoReply));
                }
                runOnUiThread(() -> {
                    appendLog("RX " + raCommand + " -> " + raReply);
                    appendLog("RX " + decCommand + " -> " + decReply);
                    appendLog("RX :MS# -> " + gotoReply);
                    busy = false;
                    gotoInProgress = true;
                    homeFindInProgress = false;
                    parked = false;
                    setStatus(sentStatus);
                    setGotoStatus(getString(R.string.goto_status_sent, target.label));
                    logStateSnapshot("goto-sent " + targetLog(target));
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    updateUiState();
                });
            } catch (CommandRejectedException ex) {
                runOnUiThread(() -> {
                    busy = false;
                    gotoInProgress = false;
                    homeFindInProgress = false;
                    Logger.warn("goto rejected command=" + ex.command + " reply=" + ex.reply + " " + targetLog(target));
                    setStatus(commandRejectedStatus(ex.command, ex.reply));
                    setGotoStatus(getString(R.string.goto_status_rejected, ex.reply));
                    logStateSnapshot("goto-rejected");
                    updateUiState();
                });
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
    }

    private boolean isSyncedCurrentTarget(SkyChartView.Target target) {
        return sameTargetCoordinates(syncedCurrentTarget, target);
    }

    private void clearSyncedCurrentTarget() {
        syncedCurrentTarget = null;
    }

    private static boolean sameTargetCoordinates(SkyChartView.Target first, SkyChartView.Target second) {
        if (first == null || second == null) {
            return false;
        }
        double raDiffDegrees = Math.abs(wrapDegrees((first.raHours - second.raHours) * 15.0));
        double decDiffDegrees = Math.abs(first.decDegrees - second.decDegrees);
        return raDiffDegrees <= 0.05 && decDiffDegrees <= 0.05;
    }

    private EquatorialPoint gotoCommandPoint(SkyChartView.Target target) {
        if (!quickPointingCorrectionActive) {
            return new EquatorialPoint(target.raHours, target.decDegrees);
        }
        return new EquatorialPoint(
                normalizeHours(target.raHours + quickPointingRaOffsetHours),
                clamp(target.decDegrees + quickPointingDecOffsetDegrees, -90.0, 90.0)
        );
    }

    private EquatorialPoint actualPointingFromMountReport(double raHours, double decDegrees) {
        if (!quickPointingCorrectionActive) {
            return new EquatorialPoint(raHours, decDegrees);
        }
        return new EquatorialPoint(
                normalizeHours(raHours - quickPointingRaOffsetHours),
                clamp(decDegrees - quickPointingDecOffsetDegrees, -90.0, 90.0)
        );
    }

    private void enableQuickPointingCorrection(double reportedRaHours, double reportedDecDegrees, SkyChartView.Target target) {
        quickPointingCorrectionActive = true;
        quickPointingRaOffsetHours = wrapDegrees((reportedRaHours - target.raHours) * 15.0) / 15.0;
        quickPointingDecOffsetDegrees = clamp(reportedDecDegrees - target.decDegrees, -45.0, 45.0);
        Logger.info("quick-pointing correction enabled " + targetLog(target)
                + " offsetRAdeg=" + formatSignedDegrees(quickPointingRaOffsetHours * 15.0)
                + " offsetDec=" + formatSignedDegrees(quickPointingDecOffsetDegrees));
    }

    private void clearQuickPointingCorrection() {
        if (quickPointingCorrectionActive) {
            Logger.info("quick-pointing correction cleared offsetRAdeg="
                    + formatSignedDegrees(quickPointingRaOffsetHours * 15.0)
                    + " offsetDec=" + formatSignedDegrees(quickPointingDecOffsetDegrees));
        }
        quickPointingCorrectionActive = false;
        quickPointingRaOffsetHours = 0.0;
        quickPointingDecOffsetDegrees = 0.0;
    }

    private void confirmSetHomeFromMountNative() {
        if (!connected || busy) {
            return;
        }
        Logger.user("tap set-home");
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_set_confirm_title)
                .setMessage(R.string.home_set_confirm_message)
                .setPositiveButton(R.string.home_set_current, (dialog, which) -> {
                    logUserAction("confirm set-home");
                    sendNativeHomeCommand(
                            OnStepCommand.HOME_CALIBRATE,
                            getString(R.string.home_set_sending),
                            getString(R.string.home_set_done),
                            R.string.home_set_failed,
                            false
                    );
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmReturnHomeNative() {
        if (!connected || busy) {
            return;
        }
        Logger.user("tap return-home");
        if (parked) {
            String status = getString(R.string.home_blocked_parked);
            Logger.warn("return-home blocked: parked");
            setStatus(status);
            setSafetyStatus(status);
            setGotoStatus(status);
            updateUiState();
            return;
        }
        if (gotoInProgress) {
            String status = getString(R.string.home_blocked_busy);
            Logger.warn("return-home blocked: goto in progress");
            setStatus(status);
            setSafetyStatus(status);
            setGotoStatus(status);
            updateUiState();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_goto_confirm_title)
                .setMessage(R.string.home_goto_confirm_message)
                .setPositiveButton(R.string.home_goto, (dialog, which) -> {
                    logUserAction("confirm return-home");
                    sendNativeHomeCommand(
                            OnStepCommand.HOME_FIND,
                            getString(R.string.home_goto_sending),
                            getString(R.string.home_goto_done),
                            R.string.home_goto_failed,
                            true
                    );
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sendNativeHomeCommand(
            OnStepCommand command,
            String sendingStatus,
            String successStatus,
            int failureStatusRes,
            boolean startsMotion
    ) {
        if (!connected || busy) {
            return;
        }
        busy = true;
        setStatus(sendingStatus);
        setSafetyStatus(sendingStatus);
        updateUiState();
        logStateSnapshot((startsMotion ? "return-home" : "set-home") + "-start");
        appendLog("TX " + command.command);

        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            try {
                String reply = client.query(command.command);
                if (reply == null || reply.trim().isEmpty() || isRejectedReply(reply.trim())) {
                    throw new CommandRejectedException(command.command, reply == null ? "" : reply.trim());
                }
                runOnUiThread(() -> {
                    appendLog("RX " + command.command + " -> " + reply);
                    busy = false;
                    if (startsMotion) {
                        gotoInProgress = true;
                        homeFindInProgress = true;
                        parked = false;
                        clearSyncedCurrentTarget();
                        setGotoStatus(successStatus);
                    }
                    setStatus(successStatus);
                    setSafetyStatus(successStatus);
                    logStateSnapshot((startsMotion ? "return-home" : "set-home") + "-success");
                    updateUiState();
                });
            } catch (CommandRejectedException ex) {
                Logger.warn((startsMotion ? "return-home" : "set-home") + " rejected: " + ex.reply);
                runOnUiThread(() -> {
                    busy = false;
                    if (startsMotion) {
                        gotoInProgress = false;
                        homeFindInProgress = false;
                    }
                    String status = getString(failureStatusRes, ex.reply == null || ex.reply.isEmpty() ? "空回包" : ex.reply);
                    setStatus(status);
                    setSafetyStatus(status);
                    if (startsMotion) {
                        setGotoStatus(status);
                    }
                    logStateSnapshot((startsMotion ? "return-home" : "set-home") + "-rejected");
                    updateUiState();
                });
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> {
                    busy = false;
                    if (startsMotion) {
                        gotoInProgress = false;
                        homeFindInProgress = false;
                    }
                    String status = getString(failureStatusRes, safeMessage(ex));
                    setStatus(status);
                    setSafetyStatus(status);
                    if (startsMotion) {
                        setGotoStatus(status);
                    }
                    logStateSnapshot((startsMotion ? "return-home" : "set-home") + "-io-failed");
                    updateUiState();
                });
            }
        });
    }

    private void parkMount() {
        if (!connected || busy) {
            return;
        }
        logUserAction("tap park");
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(OnStepCommand.PARK.command));
        runMountCommands(
                commands,
                getString(R.string.park_sending),
                getString(R.string.park_sent),
                () -> {
                    parked = true;
                    gotoInProgress = false;
                    homeFindInProgress = false;
                    trackingEnabled = false;
                    trackingUsingDualAxis = false;
                    setGotoStatus(getString(R.string.goto_status_idle));
                    setSafetyStatus(getString(R.string.safety_status_parked));
                    updateTrackingViews();
                    logStateSnapshot("park-success");
                }
        );
    }

    private void unparkMount() {
        if (!connected || busy) {
            return;
        }
        logUserAction("tap unpark");
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(OnStepCommand.UNPARK.command));
        runMountCommands(
                commands,
                getString(R.string.unpark_sending),
                getString(R.string.unpark_sent),
                () -> {
                    parked = false;
                    setSafetyStatus(getString(R.string.safety_status_unparked));
                    logStateSnapshot("unpark-success");
                }
        );
    }

    private void syncObserverToMount() {
        if (!connected || busy) {
            return;
        }
        logUserAction("tap sync-observer-to-mount " + observerLog());
        ZonedDateTime now = ZonedDateTime.now(observerState.zoneId);
        List<String> commands = buildObserverSyncCommands(now);

        busy = true;
        setStatus(getString(R.string.status_sync_mount_sending));
        updateUiState();
        logStateSnapshot("observer-sync-start");
        for (String command : commands) {
            appendLog("TX " + command);
        }

        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            try {
                for (String command : commands) {
                    client.sendNoReply(command);
                }
                runOnUiThread(() -> {
                    busy = false;
                    clearQuickPointingCorrection();
                    setStatus(getString(R.string.status_sync_mount_sent));
                    Logger.info("observer sync success " + observerLog() + " time=" + now);
                    logStateSnapshot("observer-sync-success");
                    updateUiState();
                });
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
    }

    private List<String> buildObserverSyncCommands(ZonedDateTime now) {
        List<String> commands = new ArrayList<>();
        commands.add(formatLatitudeCommand(observerState.latitudeDegrees));
        commands.add(formatLongitudeCommand(observerState.longitudeDegrees));
        commands.add(formatUtcOffsetCommand(now));
        commands.add(String.format(Locale.US, ":SL%02d:%02d:%02d#", now.getHour(), now.getMinute(), now.getSecond()));
        commands.add(String.format(Locale.US, ":SC%02d/%02d/%02d#", now.getMonthValue(), now.getDayOfMonth(), now.getYear() % 100));
        return commands;
    }

    private void setTrackingRate(TrackingRate rate) {
        logUserAction("select tracking-rate rate=" + rate.name());
        selectedTrackingRate = rate;
        updateTrackingViews();
        if (!connected || busy) {
            setStatus(getString(
                    R.string.tracking_rate_selected,
                    getString(rate.labelRes),
                    trackingModeLabel(shouldStartDualAxisTracking())
            ));
            logStateSnapshot("tracking-rate-selected-local");
            return;
        }

        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.noReply(rate.command));
        runMountCommands(
                commands,
                getString(R.string.tracking_rate_sending, getString(rate.labelRes)),
                getString(R.string.tracking_rate_sent, getString(rate.labelRes)),
                this::updateTrackingViews
        );
    }

    private void toggleTracking() {
        if (!connected || busy) {
            return;
        }

        logUserAction((trackingEnabled ? "tap tracking-stop" : "tap tracking-start")
                + " rate=" + selectedTrackingRate.name()
                + " dualAxis=" + shouldStartDualAxisTracking());
        List<MountCommand> commands = new ArrayList<>();
        String sendingStatus;
        String successStatus;
        final boolean startingDualAxis = shouldStartDualAxisTracking();
        if (trackingEnabled) {
            commands.add(MountCommand.noReply(OnStepCommand.TRACK_DISABLE.command));
            sendingStatus = getString(R.string.tracking_stop_sending);
            successStatus = getString(R.string.tracking_stop_sent);
        } else {
            addTrackingStartCommands(commands, startingDualAxis);
            commands.add(MountCommand.noReply(OnStepCommand.TRACK_ENABLE.command));
            sendingStatus = getString(
                    R.string.tracking_start_sending,
                    getString(selectedTrackingRate.labelRes),
                    trackingModeLabel(startingDualAxis)
            );
            successStatus = getString(
                    R.string.tracking_start_sent,
                    getString(selectedTrackingRate.labelRes),
                    trackingModeLabel(startingDualAxis)
            );
        }

        runMountCommands(
                commands,
                sendingStatus,
                successStatus,
                () -> {
                    if (trackingEnabled) {
                        trackingEnabled = false;
                        trackingUsingDualAxis = false;
                    } else {
                        trackingEnabled = true;
                        trackingUsingDualAxis = startingDualAxis;
                    }
                    updateTrackingViews();
                    logStateSnapshot("tracking-toggle-success");
                }
        );
    }

    private void addTrackingStartCommands(List<MountCommand> commands, boolean dualAxis) {
        addTrackingStartCommands(commands, selectedTrackingRate, dualAxis);
    }

    private void addTrackingStartCommands(List<MountCommand> commands, TrackingRate rate, boolean dualAxis) {
        commands.add(MountCommand.noReply(rate.command));
        if (dualAxis) {
            commands.add(MountCommand.noReply(OnStepCommand.TRACK_FULL_COMPENSATION.command));
            commands.add(MountCommand.noReply(OnStepCommand.TRACK_DUAL_AXIS.command));
        }
    }

    private String trackingStartCommandLog(TrackingRate rate, boolean dualAxis) {
        StringBuilder builder = new StringBuilder(rate.command);
        if (dualAxis) {
            builder.append(',').append(OnStepCommand.TRACK_FULL_COMPENSATION.command);
            builder.append(',').append(OnStepCommand.TRACK_DUAL_AXIS.command);
        }
        builder.append(',').append(OnStepCommand.TRACK_ENABLE.command);
        return builder.toString();
    }

    private boolean shouldStartDualAxisTracking() {
        return isAltAzMountMode() || hasAlignmentTrackingModel;
    }

    private boolean hasPolarRefineAlignmentModel() {
        return hasAlignmentTrackingModel && savedAlignmentStarCount >= 3;
    }

    private String trackingModeLabel(boolean dualAxis) {
        return getString(dualAxis ? R.string.tracking_mode_dual_axis : R.string.tracking_mode_single_axis);
    }

    private void fillSuggestedCalibrationTarget() {
        if (skyChartView == null) {
            return;
        }
        logUserAction("tap suggest-calibration-target mode=" + selectedCalibrationMode.name());
        List<SkyChartView.Target> suggestions = skyChartView.suggestedAlignmentTargets(12);
        if (suggestions.isEmpty()) {
            Logger.warn("calibration suggestion unavailable");
            setCalibrationStatus(getString(R.string.calibration_no_suggestion));
            return;
        }
        SkyChartView.Target target = null;
        for (int attempt = 0; attempt < suggestions.size(); attempt++) {
            SkyChartView.Target candidate = suggestions.get(suggestedCalibrationIndex % suggestions.size());
            suggestedCalibrationIndex++;
            if (!isAlignmentTargetUsed(candidate)) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            target = suggestions.get(suggestedCalibrationIndex % suggestions.size());
            suggestedCalibrationIndex++;
        }
        setCalibrationTarget(target);
        setCalibrationStatus(getString(
                R.string.calibration_suggested_status,
                target.label,
                formatRightAscensionDisplay(target.raHours),
                formatDeclinationDisplay(target.decDegrees)
        ));
        Logger.info("calibration suggested " + targetLog(target));
    }

    private boolean isAlignmentTargetUsed(SkyChartView.Target target) {
        if (alignmentSession == null || target == null) {
            return false;
        }
        if (sameTargetCoordinates(alignmentSession.currentTarget, target)) {
            return true;
        }
        for (SkyChartView.Target acceptedTarget : alignmentSession.acceptedTargets) {
            if (sameTargetCoordinates(acceptedTarget, target)) {
                return true;
            }
        }
        return false;
    }

    private void handleCalibrationTargetAction() {
        if (calibrationTargetField == null || calibrationTargetField.getText().toString().trim().isEmpty()) {
            beginCalibrationTargetSelectionInSky();
        } else {
            showCalibrationTargetInSky();
        }
    }

    private void beginCalibrationTargetSelectionInSky() {
        if (skyChartView == null || busy) {
            return;
        }
        logUserAction("tap select-calibration-target-in-sky mode=" + selectedCalibrationMode.name());
        selectingCalibrationTargetFromSky = true;
        setCalibrationStatus(getString(R.string.calibration_select_in_sky_prompt));
        updatePageTabs(Page.SKY);
        setSideMenuExpanded(false);
    }

    private void handleCalibrationTargetSelectedInSky(SkyChartView.Target target) {
        if (target == null) {
            return;
        }
        showCalibrationTargetConfirmDialog(target);
    }

    private void showCalibrationTargetConfirmDialog(SkyChartView.Target target) {
        if (calibrationTargetConfirmDialog != null && calibrationTargetConfirmDialog.isShowing()) {
            calibrationTargetConfirmDialog.dismiss();
        }
        String message = getString(
                R.string.calibration_confirm_target_message,
                target.label,
                formatRightAscensionDisplay(target.raHours),
                formatDeclinationDisplay(target.decDegrees)
        );
        if (target.solarSystemObject) {
            message = message + "\n\n" + getString(R.string.calibration_solar_system_not_allowed);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.calibration_confirm_target_title)
                .setMessage(message)
                .setNegativeButton(R.string.calibration_reselect_target, null)
                .create();
        if (!target.solarSystemObject) {
            dialog.setButton(Dialog.BUTTON_POSITIVE, getString(R.string.calibration_accept_target), (openedDialog, which) -> {
                calibrationTargetConfirmDialog = null;
                acceptCalibrationTargetFromSky(target);
            });
        }
        dialog.setOnShowListener(openedDialog -> {
            Button reselectButton = dialog.getButton(Dialog.BUTTON_NEGATIVE);
            if (reselectButton != null) {
                reselectButton.setOnClickListener(v -> {
                    calibrationTargetConfirmDialog = null;
                    dialog.dismiss();
                    selectingCalibrationTargetFromSky = true;
                    setCalibrationStatus(target.solarSystemObject
                            ? getString(R.string.calibration_reselect_after_solar_system)
                            : getString(R.string.calibration_select_in_sky_prompt));
                });
            }
        });
        dialog.setOnDismissListener(openedDialog -> {
            if (calibrationTargetConfirmDialog == dialog) {
                calibrationTargetConfirmDialog = null;
            }
        });
        calibrationTargetConfirmDialog = dialog;
        dialog.show();
    }

    private void acceptCalibrationTargetFromSky(SkyChartView.Target target) {
        logUserAction("accept calibration target from sky mode=" + selectedCalibrationMode.name()
                + " " + targetLog(target));
        selectingCalibrationTargetFromSky = false;
        setCalibrationTarget(target);
        selectedSkyTarget = target;
        if (skyChartView != null) {
            skyChartView.setSelectedTarget(target, false);
        }
        updateTargetViews();
        applyCalibrationTargetFromSky(target);
        updatePageTabs(Page.MANUAL);
    }

    private void applyCalibrationTargetFromSky(SkyChartView.Target target) {
        if (selectedCalibrationMode == CalibrationMode.QUICK_SYNC) {
            setCalibrationStatus(getString(R.string.calibration_quick_selected_manual, target.label));
            return;
        }
        if (selectedCalibrationMode.isStarAlignment()) {
            if (alignmentSession == null || alignmentSession.isComplete()) {
                setCalibrationStatus(getString(
                        R.string.calibration_suggested_status,
                        target.label,
                        formatRightAscensionDisplay(target.raHours),
                        formatDeclinationDisplay(target.decDegrees)
                ));
                updateCalibrationViews();
                return;
            }
            if (isAcceptedAlignmentTarget(target)) {
                setCalibrationStatus(getString(R.string.calibration_align_duplicate_target, target.label));
                updateCalibrationViews();
                return;
            }
            alignmentSession.currentTarget = target;
            setCalibrationStatus(getString(
                    isAltAzMountMode() ? R.string.calibration_align_selected_manual_altaz : R.string.calibration_align_selected_manual,
                    alignmentSession.currentStarNumber(),
                    alignmentSession.totalStars,
                    target.label
            ));
            updateCalibrationViews();
            return;
        }
        if (selectedCalibrationMode == CalibrationMode.REFINE_POLAR) {
            polarRefineSyncedTarget = null;
        }
        setCalibrationStatus(getString(
                R.string.calibration_suggested_status,
                target.label,
                formatRightAscensionDisplay(target.raHours),
                formatDeclinationDisplay(target.decDegrees)
        ));
        updateCalibrationViews();
    }

    private void showCalibrationTargetInSky() {
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
        logUserAction("show calibration target in sky mode=" + selectedCalibrationMode.name()
                + " " + targetLog(target));
        setCalibrationTarget(target);
        applySkyTarget(target, true);
    }

    private void selectQuickCalibrationTarget() {
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
        logUserAction("select quick-sync target " + targetLog(target));
        setCalibrationTarget(target);
        selectSkyTarget(target, true);
        setCalibrationStatus(getString(R.string.calibration_quick_selected_manual, target.label));
    }

    private void syncQuickCalibrationTarget() {
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
        setCalibrationTarget(target);
        if (!connected || busy) {
            return;
        }

        logUserAction("tap quick-sync " + targetLog(target));
        String raCommand = ":Sr" + formatRightAscensionCommand(target.raHours) + "#";
        String decCommand = ":Sd" + formatDeclinationCommand(target.decDegrees) + "#";
        String sendingStatus = getString(R.string.calibration_quick_sync_sending, target.label);
        busy = true;
        setStatus(sendingStatus);
        setCalibrationStatus(sendingStatus);
        updateUiState();
        logStateSnapshot("quick-sync-start");
        appendLog("TX " + OnStepCommand.STOP_ALL.command);
        appendLog("TX :GR#");
        appendLog("TX :GD#");
        appendLog("TX " + raCommand);
        appendLog("TX " + decCommand);
        appendLog("TX " + OnStepCommand.SYNC_CURRENT_TARGET.command);
        appendLog("TX " + OnStepCommand.TRACK_SIDEREAL.command);
        if (isAltAzMountMode()) {
            appendLog("TX " + OnStepCommand.TRACK_FULL_COMPENSATION.command);
            appendLog("TX " + OnStepCommand.TRACK_DUAL_AXIS.command);
        }
        appendLog("TX " + OnStepCommand.TRACK_ENABLE.command);
        appendLog("TX :GR#");
        appendLog("TX :GD#");

        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            List<String> replies = new ArrayList<>();
            try {
                client.sendNoReply(OnStepCommand.STOP_ALL.command);

                String beforeRaReply = client.query(":GR#");
                String beforeDecReply = client.query(":GD#");
                replies.add(":GR# -> " + beforeRaReply);
                replies.add(":GD# -> " + beforeDecReply);
                double beforeRaHours = parseRightAscension(beforeRaReply);
                double beforeDecDegrees = parseDeclination(beforeDecReply);

                String raReply = client.query(raCommand);
                replies.add(raCommand + " -> " + raReply);
                if ("0".equals(raReply) || raReply.startsWith("E")) {
                    throw new CommandRejectedException(raCommand, raReply);
                }

                String decReply = client.query(decCommand);
                replies.add(decCommand + " -> " + decReply);
                if ("0".equals(decReply) || decReply.startsWith("E")) {
                    throw new CommandRejectedException(decCommand, decReply);
                }

                String syncReply = client.query(OnStepCommand.SYNC_CURRENT_TARGET.command);
                replies.add(OnStepCommand.SYNC_CURRENT_TARGET.command + " -> " + syncReply);
                if ("0".equals(syncReply) || syncReply.startsWith("E")) {
                    throw new CommandRejectedException(OnStepCommand.SYNC_CURRENT_TARGET.command, syncReply);
                }

                client.sendNoReply(OnStepCommand.TRACK_SIDEREAL.command);
                if (isAltAzMountMode()) {
                    client.sendNoReply(OnStepCommand.TRACK_FULL_COMPENSATION.command);
                    client.sendNoReply(OnStepCommand.TRACK_DUAL_AXIS.command);
                }
                String trackingReply = client.query(OnStepCommand.TRACK_ENABLE.command);
                replies.add(OnStepCommand.TRACK_ENABLE.command + " -> " + trackingReply);
                if ("0".equals(trackingReply) || trackingReply.startsWith("E")) {
                    throw new CommandRejectedException(OnStepCommand.TRACK_ENABLE.command, trackingReply);
                }

                String afterRaReply = client.query(":GR#");
                String afterDecReply = client.query(":GD#");
                replies.add(":GR# -> " + afterRaReply);
                replies.add(":GD# -> " + afterDecReply);
                double afterRaHours = parseRightAscension(afterRaReply);
                double afterDecDegrees = parseDeclination(afterDecReply);
                double postSyncDistance = angularDistanceDegrees(
                        afterRaHours,
                        afterDecDegrees,
                        target.raHours,
                        target.decDegrees
                );

                runOnUiThread(() -> {
                    for (String reply : replies) {
                        appendLog("RX " + reply);
                    }
                    busy = false;
                    selectedTrackingRate = TrackingRate.SIDEREAL;
                    trackingEnabled = true;
                    trackingUsingDualAxis = shouldStartDualAxisTracking();
                    gotoInProgress = false;
                    setMountPointing(target.raHours, target.decDegrees);
                    syncedCurrentTarget = target;
                    if (postSyncDistance <= 0.25) {
                        clearQuickPointingCorrection();
                        setCalibrationStatus(getString(R.string.calibration_quick_sync_mount_model_sent, target.label));
                        setStatus(getString(R.string.calibration_quick_sync_mount_model_sent, target.label));
                    } else {
                        enableQuickPointingCorrection(afterRaHours, afterDecDegrees, target);
                        setCalibrationStatus(getString(
                                R.string.calibration_quick_sync_app_offset_sent,
                                target.label,
                                formatSignedDegrees(quickPointingRaOffsetHours * 15.0),
                                formatSignedDegrees(quickPointingDecOffsetDegrees)
                        ));
                        setStatus(getString(
                                R.string.calibration_quick_sync_app_offset_sent,
                                target.label,
                                formatSignedDegrees(quickPointingRaOffsetHours * 15.0),
                                formatSignedDegrees(quickPointingDecOffsetDegrees)
                        ));
                    }
                    updateTrackingViews();
                    logStateSnapshot("quick-sync-success " + targetLog(target)
                            + " postSyncDistanceDeg=" + String.format(Locale.US, "%.3f", postSyncDistance));
                    updateUiState();
                    refreshMountPointing();
                });
            } catch (CommandRejectedException ex) {
                runOnUiThread(() -> {
                    for (String reply : replies) {
                        appendLog("RX " + reply);
                    }
                    busy = false;
                    setCalibrationStatus(commandRejectedStatus(ex.command, ex.reply));
                    setStatus(commandRejectedStatus(ex.command, ex.reply));
                    Logger.warn("quick-sync rejected command=" + ex.command + " reply=" + ex.reply
                            + " " + targetLog(target));
                    logStateSnapshot("quick-sync-rejected");
                    updateUiState();
                });
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> handleCommandFailure(ex));
            } catch (IllegalArgumentException ex) {
                runOnUiThread(() -> {
                    for (String reply : replies) {
                        appendLog("RX " + reply);
                    }
                    busy = false;
                    setCalibrationStatus(getString(R.string.status_bad_pointing_reply));
                    setStatus(getString(R.string.status_bad_pointing_reply));
                    Logger.warn("quick-sync bad pointing reply " + ex.getClass().getSimpleName()
                            + " " + safeMessage(ex));
                    logStateSnapshot("quick-sync-bad-pointing");
                    updateUiState();
                });
            }
        });
    }

    private void startAlignment(int starCount) {
        if (!connected || busy || alignmentSession != null) {
            return;
        }
        logUserAction("tap start-alignment stars=" + starCount
                + " mount=" + selectedMountMode.name()
                + " " + observerLog());
        OnStepCommand startCommand;
        if (starCount == 2) {
            startCommand = OnStepCommand.ALIGN_TWO;
        } else if (starCount == 3) {
            startCommand = OnStepCommand.ALIGN_THREE;
        } else {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(observerState.zoneId);
        List<MountCommand> commands = new ArrayList<>();
        // Defensive: stop motion + abort any in-flight OnStep alignment state
        // before we sync observer and issue the new :A2#/:A3#. :A0# is a no-op
        // on firmware that does not implement abort.
        commands.add(MountCommand.noReply(OnStepCommand.STOP_ALL.command));
        commands.add(MountCommand.noReply(OnStepCommand.ALIGN_ABORT.command));
        for (String command : buildObserverSyncCommands(now)) {
            commands.add(MountCommand.noReply(command));
        }
        commands.add(MountCommand.withReply(startCommand.command));
        boolean alignmentDualAxisTracking = isAltAzMountMode();
        addTrackingStartCommands(commands, TrackingRate.SIDEREAL, alignmentDualAxisTracking);
        commands.add(MountCommand.noReply(OnStepCommand.TRACK_ENABLE.command));
        Logger.info("alignment start tracking queued stars=" + starCount
                + " rate=" + TrackingRate.SIDEREAL.name()
                + " mode=" + (alignmentDualAxisTracking ? "dual-axis" : "single-axis")
                + " mount=" + selectedMountMode.name()
                + " commands=" + trackingStartCommandLog(TrackingRate.SIDEREAL, alignmentDualAxisTracking));
        appendLog("INFO alignment start syncs observer time/location first");
        runMountCommands(
                commands,
                getString(R.string.calibration_align_starting, starCount),
                getString(R.string.calibration_align_started, starCount),
                () -> {
                    alignmentSession = new AlignmentSession(starCount);
                    hasAlignmentTrackingModel = false;
                    savedAlignmentStarCount = 0;
                    selectedTrackingRate = TrackingRate.SIDEREAL;
                    trackingEnabled = true;
                    trackingUsingDualAxis = shouldStartDualAxisTracking();
                    calibrationTarget = null;
                    calibrationTargetField.setText("");
                    clearSyncedCurrentTarget();
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    setCalibrationStatus(getString(R.string.calibration_align_started, starCount));
                    updateTrackingViews();
                    updateCalibrationViews();
                    logStateSnapshot("alignment-started stars=" + starCount);
                }
        );
    }

    private void selectAlignmentTargetOnly() {
        SkyChartView.Target target = selectAlignmentTarget(true);
        if (target == null) {
            return;
        }
        logUserAction("select alignment target star=" + alignmentSession.currentStarNumber()
                + "/" + alignmentSession.totalStars + " " + targetLog(target));
        setCalibrationStatus(getString(
                isAltAzMountMode() ? R.string.calibration_align_selected_manual_altaz : R.string.calibration_align_selected_manual,
                alignmentSession.currentStarNumber(),
                alignmentSession.totalStars,
                target.label
        ));
    }

    private SkyChartView.Target selectAlignmentTarget(boolean centerView) {
        if (alignmentSession == null || alignmentSession.isComplete()) {
            return null;
        }
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return null;
        }
        if (isAcceptedAlignmentTarget(target)) {
            setCalibrationStatus(getString(R.string.calibration_align_duplicate_target, target.label));
            return null;
        }
        setCalibrationTarget(target);
        alignmentSession.currentTarget = target;
        selectSkyTarget(target, centerView);
        updateCalibrationViews();
        return target;
    }

    private void gotoAlignmentTarget() {
        SkyChartView.Target target = selectAlignmentTarget(false);
        if (target == null) {
            return;
        }
        logUserAction("tap alignment-goto star=" + alignmentSession.currentStarNumber()
                + "/" + alignmentSession.totalStars + " " + targetLog(target));
        sendGotoTarget(
                target,
                getString(
                        R.string.calibration_align_goto_sending,
                        alignmentSession.currentStarNumber(),
                        alignmentSession.totalStars,
                        target.label
                ),
                getString(R.string.calibration_align_goto_sent, target.label),
                () -> setCalibrationStatus(getString(
                        isAltAzMountMode() ? R.string.calibration_align_goto_center_prompt_altaz : R.string.calibration_align_goto_center_prompt,
                        target.label
                ))
        );
    }

    private boolean isAcceptedAlignmentTarget(SkyChartView.Target target) {
        if (alignmentSession == null || target == null) {
            return false;
        }
        for (SkyChartView.Target acceptedTarget : alignmentSession.acceptedTargets) {
            if (sameTargetCoordinates(acceptedTarget, target)) {
                return true;
            }
        }
        return false;
    }

    private void acceptAlignmentStar() {
        if (alignmentSession == null || alignmentSession.isComplete()) {
            return;
        }
        logUserAction("tap accept-alignment-star state=" + alignmentStateSummary());
        if (alignmentSession.currentTarget == null) {
            SkyChartView.Target target = resolveCalibrationTarget();
            if (target == null) {
                return;
            }
            setCalibrationTarget(target);
            alignmentSession.currentTarget = target;
        }
        SkyChartView.Target acceptedTarget = alignmentSession.currentTarget;
        if (isAcceptedAlignmentTarget(acceptedTarget)) {
            Logger.warn("alignment accept blocked duplicate " + targetLog(acceptedTarget));
            setCalibrationStatus(getString(R.string.calibration_align_duplicate_target, acceptedTarget.label));
            return;
        }
        acceptAlignmentStarWithDiagnostics(acceptedTarget);
    }

    private void acceptAlignmentStarWithDiagnostics(SkyChartView.Target acceptedTarget) {
        if (!connected || busy || alignmentSession == null || alignmentSession.isComplete()) {
            return;
        }
        String raCommand = ":Sr" + formatRightAscensionCommand(acceptedTarget.raHours) + "#";
        String decCommand = ":Sd" + formatDeclinationCommand(acceptedTarget.decDegrees) + "#";
        String syncCommand = OnStepCommand.SYNC_CURRENT_TARGET.command;
        String sendingStatus = getString(R.string.calibration_align_accepting, acceptedTarget.label);
        int starNumber = alignmentSession.currentStarNumber();
        int totalStars = alignmentSession.totalStars;
        int acceptedBefore = alignmentSession.acceptedStars;

        busy = true;
        setStatus(sendingStatus);
        setCalibrationStatus(sendingStatus);
        updateUiState();
        appendLog("DIAG ALIGN_ACCEPT begin star=" + starNumber + "/" + totalStars
                + " acceptedBefore=" + acceptedBefore
                + " target=" + acceptedTarget.label
                + " RA=" + formatRightAscensionDisplay(acceptedTarget.raHours)
                + " Dec=" + formatDeclinationDisplay(acceptedTarget.decDegrees));
        appendLog(localAlignmentTargetDiagnostic(acceptedTarget));
        appendAlignmentDiagnosticsTx();
        appendLog("TX " + OnStepCommand.STOP_ALL.command);
        appendLog("TX " + raCommand);
        appendLog("TX " + decCommand);
        appendLog("TX " + syncCommand);

        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            List<String> diagnosticLog = new ArrayList<>();
            boolean accepted = false;
            String failureStatus = null;
            try {
                AlignmentDiagnosticSnapshot preSnapshot = collectAlignmentDiagnostics(diagnosticLog, "pre", acceptedTarget);
                client.sendNoReply(OnStepCommand.STOP_ALL.command);
                diagnosticLog.add("DIAG ALIGN_ACCEPT stop sent");

                String expectedPierSide = expectedPierSideForTarget(acceptedTarget);
                if (!isAltAzMountMode() && acceptedBefore == 0 && isPierSideMismatch(preSnapshot.pierSide, expectedPierSide)) {
                    failureStatus = getString(
                            R.string.calibration_align_pier_side_mismatch,
                            preSnapshot.pierSide,
                            expectedPierSide
                    );
                    diagnosticLog.add("DIAG ALIGN_ACCEPT skipped :CM# because pierSide="
                            + preSnapshot.pierSide + " targetPierSide=" + expectedPierSide
                            + "; use alignment GOTO to set pier side before accepting");
                } else {
                    String raReply = client.query(raCommand);
                    diagnosticLog.add("RX " + raCommand + " -> " + raReply);
                    if (isRejectedReply(raReply)) {
                        failureStatus = commandRejectedStatus(raCommand, raReply);
                    } else {
                        String decReply = client.query(decCommand);
                        diagnosticLog.add("RX " + decCommand + " -> " + decReply);
                        if (isRejectedReply(decReply)) {
                            failureStatus = commandRejectedStatus(decCommand, decReply);
                        } else {
                            try {
                                String syncReply = client.query(syncCommand);
                                diagnosticLog.add("RX " + syncCommand + " -> " + syncReply
                                        + describeReplySuffix(syncReply));
                                if (isRejectedReply(syncReply)) {
                                    failureStatus = commandRejectedStatus(syncCommand, syncReply);
                                } else {
                                    accepted = true;
                                }
                            } catch (IOException ex) {
                                failureStatus = getString(R.string.status_command_failed_keep_connected, safeMessage(ex));
                                diagnosticLog.add("DIAG ALIGN_ACCEPT " + syncCommand + " exception: "
                                        + ex.getClass().getSimpleName() + " " + safeMessage(ex));
                            }
                        }
                    }
                }
            } catch (IOException ex) {
                failureStatus = getString(R.string.status_command_failed_keep_connected, safeMessage(ex));
                diagnosticLog.add("DIAG ALIGN_ACCEPT command exception: "
                        + ex.getClass().getSimpleName() + " " + safeMessage(ex));
            }

            if (!accepted) {
                try {
                    client.sendNoReply(OnStepCommand.STOP_ALL.command);
                    diagnosticLog.add("DIAG ALIGN_ACCEPT cleanup :Q# sent");
                } catch (IOException ex) {
                    diagnosticLog.add("DIAG ALIGN_ACCEPT cleanup :Q# failed: "
                            + ex.getClass().getSimpleName() + " " + safeMessage(ex));
                }
            }
            collectAlignmentDiagnostics(diagnosticLog, "post", acceptedTarget);
            boolean finalAccepted = accepted;
            String finalFailureStatus = failureStatus == null
                    ? getString(R.string.calibration_align_diag_not_accepted)
                    : failureStatus;
            runOnUiThread(() -> {
                for (String line : diagnosticLog) {
                    appendLog(line);
                }
                busy = false;
                if (finalAccepted) {
                    setStatus(getString(R.string.calibration_align_accepted, acceptedTarget.label));
                    setCalibrationStatus(getString(R.string.calibration_align_accepted, acceptedTarget.label));
                    appendLog("DIAG ALIGN_ACCEPT app advancing to next star");
                    finishAcceptedAlignmentStar(acceptedTarget);
                    logStateSnapshot("alignment-star-accepted " + targetLog(acceptedTarget));
                } else {
                    setStatus(finalFailureStatus);
                    setCalibrationStatus(getString(R.string.calibration_align_diag_hint, finalFailureStatus));
                    appendLog("DIAG ALIGN_ACCEPT app remains at accepted="
                            + (alignmentSession == null ? "null" : alignmentSession.acceptedStars));
                    Logger.warn("alignment accept failed " + finalFailureStatus + " " + targetLog(acceptedTarget));
                    logStateSnapshot("alignment-star-accept-failed");
                    updateCalibrationViews();
                    updateUiState();
                }
            });
        });
    }

    private AlignmentDiagnosticSnapshot collectAlignmentDiagnostics(List<String> diagnosticLog, String stage, SkyChartView.Target target) {
        diagnosticLog.add("DIAG ALIGN_ACCEPT " + stage + " " + localAlignmentTargetDiagnostic(target));
        String alignReply = queryDiagnostic(":A?#");
        String pierReply = queryDiagnostic(":Gm#");
        String siderealReply = queryDiagnostic(":GS#");
        String offsetReply = queryDiagnostic(":GG#");
        String longitudeReply = queryDiagnostic(":Gg#");
        String latitudeReply = queryDiagnostic(":Gt#");
        String raReply = queryDiagnostic(":GR#");
        String decReply = queryDiagnostic(":GD#");
        String errorReply = queryDiagnostic(":GE#");
        diagnosticLog.add("RX " + stage + " :A?# -> " + alignReply);
        diagnosticLog.add("RX " + stage + " :Gm# -> " + pierReply);
        diagnosticLog.add("RX " + stage + " :GS# -> " + siderealReply);
        diagnosticLog.add("RX " + stage + " :GG# -> " + offsetReply);
        diagnosticLog.add("RX " + stage + " :Gg# -> " + longitudeReply);
        diagnosticLog.add("RX " + stage + " :Gt# -> " + latitudeReply);
        diagnosticLog.add("RX " + stage + " :GR# -> " + raReply);
        diagnosticLog.add("RX " + stage + " :GD# -> " + decReply);
        diagnosticLog.add("RX " + stage + " :GE# -> " + errorReply + describeCommandErrorSuffix(errorReply));
        try {
            double readRaHours = parseRightAscension(raReply);
            double readDecDegrees = parseDeclination(decReply);
            double distanceDegrees = angularDistanceDegrees(
                    readRaHours,
                    readDecDegrees,
                    target.raHours,
                    target.decDegrees
            );
            diagnosticLog.add("DIAG ALIGN_ACCEPT " + stage + " mountTargetDeltaDeg="
                    + String.format(Locale.US, "%.3f", distanceDegrees)
                    + " mountRA=" + formatRightAscensionDisplay(readRaHours)
                    + " mountDec=" + formatDeclinationDisplay(readDecDegrees));
        } catch (Exception ex) {
            diagnosticLog.add("DIAG ALIGN_ACCEPT " + stage + " readback exception: "
                    + ex.getClass().getSimpleName() + " " + safeMessage(ex));
        }
        return new AlignmentDiagnosticSnapshot(pierReply);
    }

    private void finishAcceptedAlignmentStar(SkyChartView.Target acceptedTarget) {
        if (alignmentSession == null || alignmentSession.isComplete()) {
            return;
        }
        alignmentSession.acceptedStars++;
        alignmentSession.acceptedTargets.add(acceptedTarget);
        alignmentSession.acceptedLabels.add(acceptedTarget.label);
        setMountPointing(acceptedTarget.raHours, acceptedTarget.decDegrees);
        if (alignmentSession.isComplete()) {
            setCalibrationStatus(getString(R.string.calibration_align_complete, alignmentSession.totalStars));
            alignmentSession.currentTarget = null;
            Logger.info("alignment complete accepted=" + alignmentSession.acceptedStars
                    + "/" + alignmentSession.totalStars
                    + " labels=" + joinLabels(alignmentSession.acceptedLabels));
        } else {
            alignmentSession.currentTarget = null;
            calibrationTarget = null;
            calibrationTargetField.setText("");
            setCalibrationStatus(getString(
                    R.string.calibration_align_next_prompt,
                    alignmentSession.currentStarNumber(),
                    alignmentSession.totalStars
            ));
        }
        clearSyncedCurrentTarget();
        clearQuickPointingCorrection();
        polarRefineSyncedTarget = null;
        updateCalibrationViews();
        updateTrackingViews();
        refreshMountPointing();
    }

    private void saveAlignmentModel() {
        if (alignmentSession == null || !alignmentSession.isComplete()) {
            return;
        }
        logUserAction("tap save-alignment-model state=" + alignmentStateSummary());
        int savedStarCount = alignmentSession.totalStars;
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(OnStepCommand.ALIGN_WRITE.command));
        if (savedStarCount >= 2) {
            addTrackingStartCommands(commands, true);
            commands.add(MountCommand.noReply(OnStepCommand.TRACK_ENABLE.command));
            Logger.info("alignment model save tracking queued stars=" + savedStarCount
                    + " rate=" + selectedTrackingRate.name()
                    + " mode=dual-axis"
                    + " commands=" + trackingStartCommandLog(selectedTrackingRate, true));
        }
        runMountCommands(
                commands,
                getString(R.string.calibration_align_saving),
                getString(R.string.calibration_align_saved),
                () -> {
                    if (savedStarCount >= 2) {
                        hasAlignmentTrackingModel = true;
                        savedAlignmentStarCount = savedStarCount;
                        trackingEnabled = true;
                        trackingUsingDualAxis = true;
                    }
                    alignmentSession = null;
                    calibrationTarget = null;
                    clearSyncedCurrentTarget();
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    setCalibrationStatus(getString(R.string.calibration_align_saved));
                    updateTrackingViews();
                    updateCalibrationViews();
                    logStateSnapshot("alignment-model-saved");
                }
        );
    }

    private void cancelAlignmentSession() {
        logUserAction("tap cancel-alignment state=" + alignmentStateSummary());
        // Reset OnStep's alignment state machine on cancel:
        // 1. :Q# stops any in-flight motion safely.
        // 2. :A0# is OnStep's abort-alignment command — it is silently ignored
        //    on firmware that does not implement it (no error reply), so it is
        //    safe to send unconditionally.
        // 3. The next :A1#/:A2#/:A3# (when the user starts a new session) will
        //    further reset the state machine on essentially all firmware.
        if (connected) {
            enqueueCommands(
                    new OnStepCommand[]{OnStepCommand.STOP_ALL, OnStepCommand.ALIGN_ABORT},
                    getString(R.string.log_stop_sent));
        }
        alignmentSession = null;
        calibrationTarget = null;
        polarRefineSyncedTarget = null;
        setCalibrationStatus(getString(R.string.calibration_align_cancelled));
        updateCalibrationViews();
        logStateSnapshot("alignment-cancelled");
    }

    private void gotoRefinePolarTarget() {
        if (isAltAzMountMode()) {
            Logger.warn("refine-polar goto blocked: altaz mode");
            setCalibrationStatus(getString(R.string.calibration_refine_disabled_altaz));
            return;
        }
        if (!hasPolarRefineAlignmentModel()) {
            Logger.warn("refine-polar goto blocked: missing three-star alignment model savedStars="
                    + savedAlignmentStarCount);
            setCalibrationStatus(getString(R.string.calibration_refine_requires_alignment_model));
            return;
        }
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
        logUserAction("tap refine-polar-goto " + targetLog(target));
        setCalibrationTarget(target);
        selectSkyTarget(target, true);
        polarRefineSyncedTarget = null;
        sendGotoTarget(
                target,
                getString(R.string.calibration_refine_goto_sending, target.label),
                getString(R.string.calibration_refine_goto_sent, target.label),
                () -> {
                    polarRefineSyncedTarget = target;
                    setCalibrationStatus(getString(R.string.calibration_refine_ready_prompt, target.label));
                    updateCalibrationViews();
                    logStateSnapshot("refine-polar-goto-ready " + targetLog(target));
                }
        );
    }

    private void refinePolarAlignment() {
        if (isAltAzMountMode()) {
            Logger.warn("refine-polar blocked: altaz mode");
            setCalibrationStatus(getString(R.string.calibration_refine_disabled_altaz));
            return;
        }
        if (!hasPolarRefineAlignmentModel()) {
            Logger.warn("refine-polar blocked: missing three-star alignment model savedStars="
                    + savedAlignmentStarCount);
            setCalibrationStatus(getString(R.string.calibration_refine_requires_alignment_model));
            return;
        }
        if (polarRefineSyncedTarget == null) {
            Logger.warn("refine-polar blocked: no synced target");
            setCalibrationStatus(getString(R.string.calibration_refine_requires_sync));
            return;
        }
        logUserAction("tap refine-polar-align " + targetLog(polarRefineSyncedTarget));
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.noReply(OnStepCommand.REFINE_POLAR_ALIGNMENT.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_refine_sending),
                getString(R.string.calibration_refine_sent),
                () -> {
                    hasAlignmentTrackingModel = true;
                    savedAlignmentStarCount = Math.max(savedAlignmentStarCount, 3);
                    clearSyncedCurrentTarget();
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    setCalibrationStatus(getString(R.string.calibration_refine_sent));
                    updateTrackingViews();
                    updateCalibrationViews();
                    logStateSnapshot("refine-polar-sent");
                }
        );
    }

    private SkyChartView.Target resolveCalibrationTarget() {
        SkyChartView.Target target = resolveTargetInput(calibrationTargetField.getText().toString());
        if (target == null) {
            setCalibrationStatus(getString(R.string.target_not_found));
            return null;
        }
        if (target.solarSystemObject) {
            setCalibrationStatus(getString(R.string.calibration_solar_system_not_allowed));
            return null;
        }
        return target;
    }

    private void setCalibrationTarget(SkyChartView.Target target) {
        calibrationTarget = target;
        if (calibrationTargetField != null) {
            calibrationTargetField.setText(target.label);
            calibrationTargetField.setSelection(calibrationTargetField.getText().length());
        }
        updateCalibrationTargetActionButton();
    }

    private void setCalibrationStatus(String status) {
        updateCalibrationStatusText(status);
        setStatus(status);
    }

    private void updateCalibrationStatusText(String status) {
        if (calibrationStatusText == null) {
            return;
        }
        calibrationStatusText.setText(status);
        calibrationStatusText.setVisibility(
                getString(R.string.calibration_status_idle).equals(status) ? View.GONE : View.VISIBLE
        );
    }

    private void updateCalibrationViews() {
        updateCalibrationTargetActionButton();
        if (calibrationStepText != null) {
            if (alignmentSession == null) {
                calibrationStepText.setText(R.string.calibration_align_idle);
            } else if (alignmentSession.isComplete()) {
                calibrationStepText.setText(getString(
                        R.string.calibration_align_progress_complete,
                        alignmentSession.acceptedStars,
                        alignmentSession.totalStars
                ));
            } else {
                calibrationStepText.setText(getString(
                        R.string.calibration_align_progress,
                        alignmentSession.currentStarNumber(),
                        alignmentSession.totalStars,
                        alignmentSession.acceptedStars
                ));
            }
        }
        if (alignmentCurrentText != null) {
            if (alignmentSession == null) {
                alignmentCurrentText.setText(R.string.calibration_align_current_none);
            } else if (alignmentSession.isComplete()) {
                alignmentCurrentText.setText(R.string.calibration_align_current_complete);
            } else if (alignmentSession.currentTarget == null) {
                alignmentCurrentText.setText(getString(
                        R.string.calibration_align_current_waiting,
                        alignmentSession.currentStarNumber(),
                        alignmentSession.totalStars
                ));
            } else {
                alignmentCurrentText.setText(getString(
                        R.string.calibration_align_current_status,
                        alignmentSession.currentStarNumber(),
                        alignmentSession.totalStars,
                        alignmentSession.currentTarget.label,
                        formatRightAscensionDisplay(alignmentSession.currentTarget.raHours),
                        formatDeclinationDisplay(alignmentSession.currentTarget.decDegrees)
                ));
            }
        }
        if (alignmentAcceptedText != null) {
            if (alignmentSession == null || alignmentSession.acceptedLabels.isEmpty()) {
                alignmentAcceptedText.setText(R.string.calibration_align_accepted_none);
            } else {
                alignmentAcceptedText.setText(getString(
                        R.string.calibration_align_accepted_list,
                        joinLabels(alignmentSession.acceptedLabels)
                ));
            }
        }
        updateUiState();
    }

    private void updateCalibrationTargetActionButton() {
        if (calibrationShowButton == null || calibrationTargetField == null) {
            return;
        }
        boolean hasInput = calibrationTargetField.getText() != null
                && !calibrationTargetField.getText().toString().trim().isEmpty();
        calibrationShowButton.setText(hasInput ? R.string.calibration_show_in_sky : R.string.calibration_select_in_sky);
    }

    private static String joinLabels(List<String> labels) {
        StringBuilder builder = new StringBuilder();
        for (String label : labels) {
            if (builder.length() > 0) {
                builder.append("、");
            }
            builder.append(label);
        }
        return builder.toString();
    }

    private void refreshCalibrationModeChoices() {
        if (calibrationModeSpinner == null) {
            return;
        }
        List<CalibrationMode> modes = availableCalibrationModes();
        if (!modes.contains(selectedCalibrationMode)) {
            selectedCalibrationMode = CalibrationMode.QUICK_SYNC;
        }
        List<String> labels = new ArrayList<>();
        for (CalibrationMode mode : modes) {
            labels.add(getString(mode.labelRes));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        suppressCalibrationModeSelection = true;
        calibrationModeSpinner.setAdapter(adapter);
        calibrationModeSpinner.setSelection(Math.max(0, modes.indexOf(selectedCalibrationMode)));
        suppressCalibrationModeSelection = false;
        calibrationModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressCalibrationModeSelection || position < 0 || position >= modes.size()) {
                    return;
                }
                selectedCalibrationMode = modes.get(position);
                updateCalibrationModeViews();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateCalibrationModeViews();
    }

    private List<CalibrationMode> availableCalibrationModes() {
        List<CalibrationMode> modes = new ArrayList<>();
        for (CalibrationMode mode : CalibrationMode.values()) {
            if (mode == CalibrationMode.REFINE_POLAR && isAltAzMountMode()) {
                continue;
            }
            modes.add(mode);
        }
        return modes;
    }

    private void updateCalibrationModeViews() {
        if (isAltAzMountMode() && selectedCalibrationMode == CalibrationMode.REFINE_POLAR) {
            selectedCalibrationMode = CalibrationMode.QUICK_SYNC;
        }
        boolean quickMode = selectedCalibrationMode == CalibrationMode.QUICK_SYNC;
        boolean alignMode = selectedCalibrationMode.isStarAlignment();
        boolean refineMode = selectedCalibrationMode == CalibrationMode.REFINE_POLAR;

        if (quickCalibrationPanel != null) {
            quickCalibrationPanel.setVisibility(quickMode ? View.VISIBLE : View.GONE);
        }
        if (alignCalibrationPanel != null) {
            alignCalibrationPanel.setVisibility(alignMode ? View.VISIBLE : View.GONE);
        }
        if (refineCalibrationPanel != null) {
            refineCalibrationPanel.setVisibility(refineMode ? View.VISIBLE : View.GONE);
        }
        if (alignStartButton != null && alignMode) {
            alignStartButton.setText(getString(R.string.calibration_align_start_count, selectedCalibrationMode.starCount));
        }
        if (alignGotoButton != null) {
            alignGotoButton.setText(isAltAzMountMode()
                    ? R.string.calibration_align_goto_altaz
                    : R.string.calibration_align_goto);
        }
        if (hostField != null) {
            updateUiState();
        }
    }

    private void runMountCommands(List<MountCommand> commands, String sendingStatus, String successStatus, Runnable onSuccess) {
        if (!connected || busy) {
            return;
        }
        busy = true;
        setStatus(sendingStatus);
        updateCalibrationStatusText(sendingStatus);
        updateUiState();
        Logger.info("mount-command-batch start count=" + commands.size() + " status=\"" + sendingStatus + "\"");
        logStateSnapshot("mount-command-batch-start");
        for (MountCommand command : commands) {
            appendLog("TX " + command.command);
        }

        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            List<String> replies = new ArrayList<>();
            try {
                for (MountCommand command : commands) {
                    if (command.expectReply) {
                        String reply = client.query(command.command);
                        replies.add(command.command + " -> " + reply);
                        if ("0".equals(reply) || reply.startsWith("E")) {
                            throw new CommandRejectedException(command.command, reply);
                        }
                    } else {
                        client.sendNoReply(command.command);
                    }
                }
                runOnUiThread(() -> {
                    for (String reply : replies) {
                        appendLog("RX " + reply);
                    }
                    busy = false;
                    setStatus(successStatus);
                    updateCalibrationStatusText(successStatus);
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    Logger.info("mount-command-batch success count=" + commands.size() + " status=\"" + successStatus + "\"");
                    logStateSnapshot("mount-command-batch-success");
                    updateUiState();
                });
            } catch (CommandRejectedException ex) {
                runOnUiThread(() -> {
                    for (String reply : replies) {
                        appendLog("RX " + reply);
                    }
                    busy = false;
                    setCalibrationStatus(commandRejectedStatus(ex.command, ex.reply));
                    setStatus(commandRejectedStatus(ex.command, ex.reply));
                    Logger.warn("mount-command-batch rejected command=" + ex.command + " reply=" + ex.reply);
                    logStateSnapshot("mount-command-batch-rejected");
                    updateUiState();
                });
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
    }

    private static boolean isRejectedReply(String reply) {
        return "0".equals(reply) || (reply != null && reply.startsWith("E"));
    }

    private String commandRejectedStatus(String command, String reply) {
        String status = getString(R.string.status_command_rejected, command, reply);
        String description = describeOnStepReply(reply);
        return description.isEmpty() ? status : status + " (" + description + ")";
    }

    private void appendAlignmentDiagnosticsTx() {
        String[] diagnosticCommands = {
                ":A?#", ":Gm#", ":GS#", ":GG#", ":Gg#", ":Gt#", ":GR#", ":GD#", ":GE#"
        };
        for (String command : diagnosticCommands) {
            appendLog("TX " + command);
        }
    }

    private String queryDiagnostic(String command) {
        try {
            return client.query(command);
        } catch (IOException ex) {
            return "<" + ex.getClass().getSimpleName() + " " + safeMessage(ex) + ">";
        }
    }

    private String localAlignmentTargetDiagnostic(SkyChartView.Target target) {
        Instant now = Instant.now();
        double localSiderealHours = localSiderealDegrees(now) / 15.0;
        double hourAngleHours = signedHourAngleHours(target.raHours, now);
        HorizontalCoordinates coordinates = horizontalCoordinates(target.raHours, target.decDegrees, now);
        return String.format(
                Locale.US,
                "DIAG target appTime=%s appLST=%s HA=%s Alt=%.1f Az=%.1f observerLat=%.5f observerLon=%.5f",
                observerState.formatTime(now),
                formatRightAscensionDisplay(localSiderealHours),
                formatHourAngleDisplay(hourAngleHours),
                coordinates.altitudeDegrees,
                coordinates.azimuthDegrees,
                observerState.latitudeDegrees,
                observerState.longitudeDegrees
        );
    }

    private String expectedPierSideForTarget(SkyChartView.Target target) {
        double hourAngleHours = signedHourAngleHours(target.raHours, Instant.now());
        return hourAngleHours >= 0.0 ? "E" : "W";
    }

    private static boolean isPierSideMismatch(String currentPierSide, String targetPierSide) {
        return ("E".equals(currentPierSide) || "W".equals(currentPierSide))
                && ("E".equals(targetPierSide) || "W".equals(targetPierSide))
                && !currentPierSide.equals(targetPierSide);
    }

    private static String describeReplySuffix(String reply) {
        String description = describeOnStepReply(reply);
        return description.isEmpty() ? "" : " (" + description + ")";
    }

    private static String describeCommandErrorSuffix(String reply) {
        if (reply == null) {
            return "";
        }
        String clean = reply.trim();
        try {
            int code = Integer.parseInt(clean);
            switch (code) {
                case 0:
                    return " (CE_NONE: no error)";
                case 6:
                    return " (CE_ALIGN_FAIL: align failed)";
                case 7:
                    return " (CE_ALIGN_NOT_ACTIVE: align not active)";
                case 15:
                    return " (CE_GOTO_ERR_BELOW_HORIZON: target below horizon)";
                case 16:
                    return " (CE_GOTO_ERR_ABOVE_OVERHEAD: target above overhead)";
                case 17:
                    return " (CE_SLEW_ERR_IN_STANDBY: mount in standby)";
                case 18:
                    return " (CE_SLEW_ERR_IN_PARK: mount parked)";
                case 19:
                    return " (CE_GOTO_ERR_GOTO: already in goto)";
                case 20:
                    return " (CE_SLEW_ERR_OUTSIDE_LIMITS: outside limits or pier-side change refused)";
                case 21:
                    return " (CE_SLEW_ERR_HARDWARE_FAULT: hardware fault)";
                case 22:
                    return " (CE_MOUNT_IN_MOTION: mount in motion)";
                case 23:
                    return " (CE_GOTO_ERR_UNSPECIFIED: other goto/sync error)";
                default:
                    return "";
            }
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private static String describeOnStepReply(String reply) {
        if (reply == null) {
            return "";
        }
        switch (reply.trim()) {
            case "0":
                return "reply 0: command failed";
            case "E1":
                return "E1: target below horizon";
            case "E2":
                return "E2: target above overhead limit";
            case "E3":
                return "E3: mount in standby";
            case "E4":
                return "E4: mount parked";
            case "E5":
                return "E5: already in goto";
            case "E6":
                return "E6: outside limits or pier-side change refused";
            case "E7":
                return "E7: hardware fault";
            case "E8":
                return "E8: mount in motion";
            case "E9":
                return "E9: other goto/sync error";
            default:
                return "";
        }
    }

    private void refreshMountPointing() {
        if (!connected || busy || mountPointingPollingPaused) {
            return;
        }
        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            try {
                String raReply = client.query(":GR#");
                String decReply = client.query(":GD#");
                double raHours = parseRightAscension(raReply);
                double decDegrees = parseDeclination(decReply);
                runOnUiThread(() -> {
                    mountPointingFailureCount = 0;
                    mountPointingPollingPaused = false;
                    setMountPointingFromMount(raHours, decDegrees);
                });
            } catch (SocketTimeoutException ex) {
                runOnUiThread(() -> handleMountPointingPollFailure(ex));
            } catch (IOException ex) {
                runOnUiThread(() -> handleMountPointingPollFailure(ex));
            } catch (IllegalArgumentException ex) {
                runOnUiThread(() -> {
                    mountPointingFailureCount++;
                    appendLog("WARN bad pointing reply " + safeMessage(ex));
                    setStatus(getString(R.string.status_bad_pointing_reply));
                });
            }
        });
    }

    private void handleMountPointingPollFailure(IOException ex) {
        mountPointingFailureCount++;
        String reason = safeMessage(ex);
        appendLog("WARN pointing poll " + reason);
        if (mountPointingFailureCount >= 3) {
            mountPointingPollingPaused = true;
            appendLog("INFO pointing poll paused");
            setStatus(getString(R.string.status_pointing_poll_paused, mountPointingFailureCount, reason));
        } else {
            setStatus(getString(R.string.status_pointing_poll_failed, mountPointingFailureCount, reason));
        }
    }

    private void setMountPointing(double raHours, double decDegrees) {
        hasCurrentMountPosition = true;
        currentMountRaHours = normalizeHours(raHours);
        currentMountDecDegrees = clamp(decDegrees, -90.0, 90.0);
        if (skyChartView != null) {
            skyChartView.setMountEquatorial(currentMountRaHours, currentMountDecDegrees);
        }
        if (mountPointingText != null) {
            mountPointingText.setText(getString(
                    R.string.mount_pointing_status,
                    formatRightAscensionDisplay(currentMountRaHours),
                    formatDeclinationDisplay(currentMountDecDegrees)
            ));
        }
        updateGotoProgressFromPointing();
    }

    private void setMountPointingFromMount(double raHours, double decDegrees) {
        EquatorialPoint actualPointing = actualPointingFromMountReport(raHours, decDegrees);
        setMountPointing(actualPointing.raHours, actualPointing.decDegrees);
    }

    private void clearMountPointing() {
        hasCurrentMountPosition = false;
        if (skyChartView != null) {
            skyChartView.clearMountEquatorial();
        }
        if (mountPointingText != null) {
            mountPointingText.setText(R.string.mount_pointing_default);
        }
    }

    private void loadFirmwarePreferences() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        selectedFirmwareMode = parseEnumPreference(
                preferences.getString(PREF_FIRMWARE_MODE, FirmwareMode.ONSTEP.name()),
                FirmwareMode.ONSTEP
        );
        selectedMountMode = parseEnumPreference(
                preferences.getString(PREF_MOUNT_MODE, MountMode.EQUATORIAL.name()),
                MountMode.EQUATORIAL
        );
        if (selectedFirmwareMode == FirmwareMode.ONSTEP) {
            selectedMountMode = MountMode.EQUATORIAL;
        }
    }

    private void saveFirmwarePreferences() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_FIRMWARE_MODE, selectedFirmwareMode.name())
                .putString(PREF_MOUNT_MODE, selectedMountMode.name())
                .apply();
    }

    private static <T extends Enum<T>> T parseEnumPreference(String value, T fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private boolean isAltAzMountMode() {
        return selectedFirmwareMode == FirmwareMode.ONSTEPX && selectedMountMode == MountMode.ALTAZ;
    }

    private void updateFirmwareSettingsViews() {
        if (mountModeContainer != null) {
            mountModeContainer.setVisibility(selectedFirmwareMode == FirmwareMode.ONSTEPX ? View.VISIBLE : View.GONE);
        }
        if (mountModeSpinner != null) {
            suppressMountModeSelection = true;
            mountModeSpinner.setSelection(selectedMountMode.ordinal());
            suppressMountModeSelection = false;
            mountModeSpinner.setEnabled(!busy);
        }
        if (firmwareModeSpinner != null) {
            suppressFirmwareModeSelection = true;
            firmwareModeSpinner.setSelection(selectedFirmwareMode.ordinal());
            suppressFirmwareModeSelection = false;
            firmwareModeSpinner.setEnabled(!busy);
        }
        if (firmwareSettingsStatusText != null) {
            if (selectedFirmwareMode == FirmwareMode.ONSTEP) {
                firmwareSettingsStatusText.setText(R.string.firmware_settings_status_onstep);
            } else if (selectedMountMode == MountMode.ALTAZ) {
                firmwareSettingsStatusText.setText(R.string.firmware_settings_status_onstepx_altaz);
            } else {
                firmwareSettingsStatusText.setText(R.string.firmware_settings_status_onstepx_equatorial);
            }
        }
    }

    private void requestMountModeChange(MountMode requestedMode) {
        MountMode previousMode = selectedMountMode;
        logUserAction("select mount-mode requested=" + requestedMode.name()
                + " previous=" + previousMode.name()
                + " connected=" + connected);
        if (!connected || selectedFirmwareMode != FirmwareMode.ONSTEPX) {
            selectedMountMode = requestedMode;
            saveFirmwarePreferences();
            updateFirmwareSettingsViews();
            refreshCalibrationModeChoices();
            updateCalibrationViews();
            updateTrackingViews();
            if (firmwareSettingsStatusText != null) {
                firmwareSettingsStatusText.setText(getString(R.string.mount_mode_selected_local, getString(requestedMode.labelRes)));
            }
            logStateSnapshot("mount-mode-selected-local");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.mount_mode_switch_title)
                .setMessage(getString(R.string.mount_mode_switch_message, getString(requestedMode.labelRes)))
                .setPositiveButton(R.string.mount_mode_switch_confirm, (dialog, which) ->
                        applyConnectedMountModeChange(requestedMode, previousMode))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    selectedMountMode = previousMode;
                    updateFirmwareSettingsViews();
                })
                .show();
    }

    private void applyConnectedMountModeChange(MountMode requestedMode, MountMode previousMode) {
        if (!connected || busy) {
            selectedMountMode = previousMode;
            updateFirmwareSettingsViews();
            Logger.warn("mount-mode switch blocked connected=" + connected + " busy=" + busy);
            return;
        }
        logUserAction("confirm connected mount-mode switch requested=" + requestedMode.name()
                + " previous=" + previousMode.name());
        String command = String.format(Locale.US, ":SXEM,%d#", requestedMode.onStepXCode);
        String sendingStatus = getString(R.string.mount_mode_switch_sending, getString(requestedMode.labelRes));
        busy = true;
        setStatus(sendingStatus);
        if (firmwareSettingsStatusText != null) {
            firmwareSettingsStatusText.setText(sendingStatus);
        }
        updateUiState();
        logStateSnapshot("mount-mode-switch-start");
        appendLog("TX " + command);

        int generation = connectionGeneration.get();
        ioExecutor.execute(() -> {
            if (!isConnectionGenerationCurrent(generation)) {
                return;
            }
            List<String> switchLog = new ArrayList<>();
            boolean commandSent = false;
            try {
                client.sendNoReply(command);
                commandSent = true;
                MountMode verifiedMode = queryOnStepXMountModeCode(switchLog);
                runOnUiThread(() -> {
                    for (String entry : switchLog) {
                        appendLog(entry);
                    }
                    busy = false;
                    if (verifiedMode != requestedMode) {
                        finishMountModeWriteAndDisconnect(
                                requestedMode,
                                R.string.mount_mode_switch_unverified,
                                getString(R.string.mount_mode_switch_unverified_mismatch, getString(verifiedMode.labelRes))
                        );
                    } else {
                        finishMountModeWriteAndDisconnect(requestedMode, R.string.mount_mode_switch_done);
                    }
                });
            } catch (IOException ex) {
                boolean wroteCommand = commandSent;
                if (!wroteCommand) {
                    markTransportFault();
                }
                runOnUiThread(() -> {
                    for (String entry : switchLog) {
                        appendLog(entry);
                    }
                    busy = false;
                    if (wroteCommand) {
                        finishMountModeWriteAndDisconnect(
                                requestedMode,
                                R.string.mount_mode_switch_unverified,
                                safeMessage(ex)
                        );
                    } else {
                        selectedMountMode = previousMode;
                        setStatus(getString(R.string.mount_mode_switch_failed, safeMessage(ex)));
                        Logger.error("mount-mode switch failed before write requested=" + requestedMode.name(), ex);
                        updateFirmwareSettingsViews();
                        updateUiState();
                    }
                });
            }
        });
    }

    private MountMode queryOnStepXMountModeCode(List<String> switchLog) throws IOException {
        switchLog.add("TX " + ONSTEPX_MOUNT_MODE_QUERY);
        String reply = client.query(ONSTEPX_MOUNT_MODE_QUERY);
        switchLog.add("RX " + ONSTEPX_MOUNT_MODE_QUERY + " -> " + reply);
        MountMode mode = parseOnStepXMountModeCode(reply);
        if (mode == null) {
            throw new IOException(getString(R.string.mount_mode_switch_unrecognized_reply, reply));
        }
        return mode;
    }

    private MountMode parseOnStepXMountModeCode(String reply) {
        String trimmed = reply == null ? "" : reply.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isRejectedReply(trimmed)) {
            return null;
        }
        for (MountMode mode : MountMode.values()) {
            if (trimmed.equals(String.valueOf(mode.onStepXCode))) {
                return mode;
            }
        }
        if ("2".equals(trimmed)) {
            return MountMode.EQUATORIAL;
        }
        return null;
    }

    private void finishMountModeWriteAndDisconnect(MountMode requestedMode, int statusRes) {
        finishMountModeWriteAndDisconnect(requestedMode, statusRes, null);
    }

    private void finishMountModeWriteAndDisconnect(MountMode requestedMode, int statusRes, String detail) {
        selectedMountMode = requestedMode;
        saveFirmwarePreferences();
        String status = detail == null
                ? getString(statusRes, getString(requestedMode.labelRes))
                : getString(statusRes, getString(requestedMode.labelRes), detail);
        setStatus(status);
        if (firmwareSettingsStatusText != null) {
            firmwareSettingsStatusText.setText(status);
        }
        Logger.info("mount-mode write finished requested=" + requestedMode.name()
                + (detail == null ? "" : " detail=" + detail));
        logStateSnapshot("mount-mode-write-finished");
        new AlertDialog.Builder(this)
                .setTitle(R.string.mount_mode_switch_done_title)
                .setMessage(R.string.mount_mode_switch_done_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        updateFirmwareSettingsViews();
        refreshCalibrationModeChoices();
        updateCalibrationViews();
        updateTrackingViews();
        updateUiState();
        disconnect();
    }

    private void updateTargetViews() {
        if (targetStatusText != null) {
            if (selectedSkyTarget == null) {
                targetStatusText.setText(R.string.sky_target_none);
            } else {
                targetStatusText.setText(getString(
                        R.string.sky_target_status,
                        selectedSkyTarget.label,
                        formatRightAscensionDisplay(selectedSkyTarget.raHours),
                        formatDeclinationDisplay(selectedSkyTarget.decDegrees)
                ));
            }
        }
        updateObservingAlert();
        updateUiState();
    }

    private void updateObservingAlert() {
        if (observingAlertText == null) {
            return;
        }
        if (selectedSkyTarget == null) {
            observingAlertText.setText(R.string.observing_alert_no_target);
            return;
        }

        Instant now = Instant.now();
        HorizontalCoordinates coordinates = horizontalCoordinates(
                selectedSkyTarget.raHours,
                selectedSkyTarget.decDegrees,
                now
        );
        double localSiderealDegrees = localSiderealDegrees(now);
        double hourAngleHours = wrapDegrees(localSiderealDegrees - selectedSkyTarget.raHours * 15.0) / 15.0;
        double minutesFromMeridian = Math.abs(hourAngleHours) * 60.0;
        String meridian = meridianStatus(hourAngleHours);

        if (coordinates.altitudeDegrees < 0.0) {
            observingAlertText.setText(getString(
                    R.string.observing_alert_below_horizon,
                    coordinates.altitudeDegrees,
                    coordinates.azimuthDegrees,
                    meridian
            ));
        } else if (coordinates.altitudeDegrees < 15.0) {
            observingAlertText.setText(getString(
                    R.string.observing_alert_low_altitude,
                    coordinates.altitudeDegrees,
                    coordinates.azimuthDegrees,
                    meridian
            ));
        } else if (minutesFromMeridian <= 30.0) {
            observingAlertText.setText(getString(
                    R.string.observing_alert_meridian,
                    coordinates.altitudeDegrees,
                    coordinates.azimuthDegrees,
                    meridian
            ));
        } else {
            observingAlertText.setText(getString(
                    R.string.observing_alert_ok,
                    coordinates.altitudeDegrees,
                    coordinates.azimuthDegrees,
                    meridian
            ));
        }
    }

    private void updateGotoProgressFromPointing() {
        if (!gotoInProgress || homeFindInProgress || selectedSkyTarget == null || !hasCurrentMountPosition) {
            return;
        }
        double distanceDegrees = angularDistanceDegrees(
                currentMountRaHours,
                currentMountDecDegrees,
                selectedSkyTarget.raHours,
                selectedSkyTarget.decDegrees
        );
        if (distanceDegrees <= 0.25) {
            gotoInProgress = false;
            setGotoStatus(getString(R.string.goto_status_arrived, selectedSkyTarget.label));
            updateUiState();
        }
    }

    private HorizontalCoordinates horizontalCoordinates(double raHours, double decDegrees, Instant instant) {
        double hourAngle = Math.toRadians(wrapDegrees(localSiderealDegrees(instant) - raHours * 15.0));
        double dec = Math.toRadians(decDegrees);
        double lat = Math.toRadians(observerState.latitudeDegrees);

        double sinAlt = Math.sin(dec) * Math.sin(lat) + Math.cos(dec) * Math.cos(lat) * Math.cos(hourAngle);
        double altitude = Math.asin(clamp(sinAlt, -1.0, 1.0));
        double cosAlt = Math.max(1.0e-8, Math.cos(altitude));
        double cosLat = Math.cos(lat);
        double sinAz = -Math.cos(dec) * Math.sin(hourAngle) / cosAlt;
        double cosAz = Math.abs(cosLat) < 1.0e-8
                ? 1.0
                : (Math.sin(dec) - Math.sin(altitude) * Math.sin(lat)) / (cosAlt * cosLat);
        double azimuth = Math.toDegrees(Math.atan2(sinAz, cosAz));
        return new HorizontalCoordinates(Math.toDegrees(altitude), normalizeDegrees(azimuth));
    }

    private double localSiderealDegrees(Instant instant) {
        return normalizeDegrees(greenwichSiderealDegrees(instant) + observerState.longitudeDegrees);
    }

    private static double greenwichSiderealDegrees(Instant instant) {
        double jd = instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5;
        double d = jd - 2_451_545.0;
        double t = d / 36_525.0;
        double gmst = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38_710_000.0;
        return normalizeDegrees(gmst);
    }

    private double signedHourAngleHours(double raHours, Instant instant) {
        double hourAngle = normalizeHours(localSiderealDegrees(instant) / 15.0 - normalizeHours(raHours));
        return hourAngle > 12.0 ? hourAngle - 24.0 : hourAngle;
    }

    private String meridianStatus(double hourAngleHours) {
        double minutes = Math.abs(hourAngleHours) * 60.0;
        if (minutes < 3.0) {
            return getString(R.string.meridian_now);
        }
        if (hourAngleHours < 0.0) {
            return getString(R.string.meridian_before, minutes);
        }
        return getString(R.string.meridian_after, minutes);
    }

    private static double angularDistanceDegrees(double firstRaHours, double firstDecDegrees, double secondRaHours, double secondDecDegrees) {
        double ra1 = Math.toRadians(normalizeHours(firstRaHours) * 15.0);
        double dec1 = Math.toRadians(firstDecDegrees);
        double ra2 = Math.toRadians(normalizeHours(secondRaHours) * 15.0);
        double dec2 = Math.toRadians(secondDecDegrees);
        double cosDistance = Math.sin(dec1) * Math.sin(dec2)
                + Math.cos(dec1) * Math.cos(dec2) * Math.cos(ra1 - ra2);
        return Math.toDegrees(Math.acos(clamp(cosDistance, -1.0, 1.0)));
    }

    private String describeGotoReply(String reply) {
        if ("1".equals(reply)) {
            return getString(R.string.goto_reply_below_horizon);
        }
        if ("2".equals(reply)) {
            return getString(R.string.goto_reply_above_overhead);
        }
        if ("3".equals(reply)) {
            return getString(R.string.goto_reply_standby);
        }
        if ("4".equals(reply)) {
            return getString(R.string.goto_reply_parked);
        }
        if ("5".equals(reply)) {
            return getString(R.string.goto_reply_in_progress);
        }
        if ("6".equals(reply)) {
            return getString(R.string.goto_reply_outside_limits);
        }
        if ("7".equals(reply)) {
            return getString(R.string.goto_reply_hardware_fault);
        }
        if ("8".equals(reply)) {
            return getString(R.string.goto_reply_already_moving);
        }
        if ("9".equals(reply)) {
            return getString(R.string.goto_reply_unknown);
        }
        return reply == null || reply.isEmpty() ? getString(R.string.goto_reply_empty) : reply;
    }

    private static String formatRightAscensionCommand(double raHours) {
        int totalSeconds = (int) Math.round(normalizeHours(raHours) * 3600.0);
        totalSeconds %= 24 * 3600;
        int hours = totalSeconds / 3600;
        int minutes = totalSeconds % 3600 / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static String formatDeclinationCommand(double decDegrees) {
        double clamped = Math.max(-90.0, Math.min(90.0, decDegrees));
        int totalSeconds = (int) Math.round(Math.abs(clamped) * 3600.0);
        int degrees = Math.min(90, totalSeconds / 3600);
        int minutes = totalSeconds % 3600 / 60;
        int seconds = totalSeconds % 60;
        if (degrees == 90) {
            minutes = 0;
            seconds = 0;
        }
        return String.format(Locale.US, "%c%02d*%02d:%02d", clamped < 0.0 ? '-' : '+', degrees, minutes, seconds);
    }

    private static String formatLatitudeCommand(double latitudeDegrees) {
        double clamped = Math.max(-90.0, Math.min(90.0, latitudeDegrees));
        int totalMinutes = (int) Math.round(Math.abs(clamped) * 60.0);
        int degrees = Math.min(90, totalMinutes / 60);
        int minutes = totalMinutes % 60;
        if (degrees == 90) {
            minutes = 0;
        }
        return String.format(Locale.US, ":St%c%02d*%02d#", clamped < 0.0 ? '-' : '+', degrees, minutes);
    }

    private static String formatLongitudeCommand(double longitudeDegrees) {
        double normalized = ((longitudeDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        int totalMinutes = (int) Math.round(Math.abs(normalized) * 60.0);
        int degrees = Math.min(180, totalMinutes / 60);
        int minutes = totalMinutes % 60;
        if (degrees == 180) {
            minutes = 0;
        }
        char onStepSign = normalized < 0.0 ? '+' : '-';
        return String.format(Locale.US, ":Sg%c%03d*%02d#", onStepSign, degrees, minutes);
    }

    private static String formatUtcOffsetCommand(ZonedDateTime dateTime) {
        int totalSeconds = dateTime.getOffset().getTotalSeconds();
        int roundedHours = (int) Math.round(Math.abs(totalSeconds) / 3600.0);
        char onStepSign = totalSeconds < 0 ? '+' : '-';
        return String.format(Locale.US, ":SG%c%02d#", onStepSign, roundedHours);
    }

    private static String formatRightAscensionDisplay(double raHours) {
        return formatRightAscensionCommand(raHours);
    }

    private static String formatHourAngleDisplay(double hourAngleHours) {
        double signed = hourAngleHours;
        if (signed > 12.0) {
            signed -= 24.0;
        } else if (signed < -12.0) {
            signed += 24.0;
        }
        int totalSeconds = (int) Math.round(Math.abs(signed) * 3600.0);
        int hours = totalSeconds / 3600;
        int minutes = totalSeconds % 3600 / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%c%02d:%02d:%02d", signed < 0.0 ? '-' : '+', hours, minutes, seconds);
    }

    private static String formatDeclinationDisplay(double decDegrees) {
        String command = formatDeclinationCommand(decDegrees);
        return command.replace('*', '°');
    }

    private static String formatSignedDegrees(double degrees) {
        return String.format(Locale.US, "%+.2f°", degrees);
    }

    private static double parseRightAscension(String value) {
        String clean = value.trim();
        if (clean.startsWith("-") || clean.startsWith("+")) {
            throw new IllegalArgumentException("RA must be in 0..24h, no sign: " + value);
        }
        double result;
        if (!clean.contains(":")) {
            result = Double.parseDouble(clean);
        } else {
            String[] parts = clean.split(":");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid RA: " + value);
            }
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = parts.length >= 3 ? Double.parseDouble(parts[2]) : 0.0;
            if (hours < 0 || minutes < 0 || seconds < 0) {
                throw new IllegalArgumentException("RA components must be non-negative: " + value);
            }
            result = hours + minutes / 60.0 + seconds / 3600.0;
        }
        if (result < 0.0 || result >= 24.0) {
            throw new IllegalArgumentException("RA must be in 0..24h: " + value);
        }
        return result;
    }

    private static double parseDeclination(String value) {
        String normalized = value.trim()
                .replace('*', ':')
                .replace('°', ':')
                .replace('\'', ':')
                .replace("\"", "");
        boolean negative = normalized.startsWith("-");
        normalized = normalized.replace("+", "").replace("-", "");
        if (!normalized.contains(":")) {
            double result = Double.parseDouble(normalized);
            return negative ? -result : result;
        }
        String[] parts = normalized.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid Dec: " + value);
        }
        double degrees = Double.parseDouble(parts[0]);
        double minutes = Double.parseDouble(parts[1]);
        double seconds = parts.length >= 3 && !parts[2].isEmpty() ? Double.parseDouble(parts[2]) : 0.0;
        double result = degrees + minutes / 60.0 + seconds / 3600.0;
        return negative ? -result : result;
    }

    private static double normalizeHours(double hours) {
        double result = hours % 24.0;
        return result < 0.0 ? result + 24.0 : result;
    }

    private static double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0.0 ? result + 360.0 : result;
    }

    private static double wrapDegrees(double degrees) {
        double result = normalizeDegrees(degrees);
        return result > 180.0 ? result - 360.0 : result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String getRateCommand() {
        return selectedManualRate.command;
    }

    private Button directionButton(int labelRes, Direction direction) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(labelRes);
        button.setTextSize(24);
        button.setTextColor(Color.rgb(17, 24, 39));
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(58));
        button.setMinWidth(dp(58));
        button.setBackground(createDirectionButtonBackground(true));
        button.setOnTouchListener((view, event) -> {
            if (!connected || busy) {
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    startMove(direction);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    stopMove(direction);
                    view.performClick();
                    return true;
                default:
                    return true;
            }
        });
        return button;
    }

    private Button trackingRateButton(TrackingRate rate) {
        Button button = actionButton(rate.labelRes);
        button.setTextSize(14);
        button.setOnClickListener(v -> setTrackingRate(rate));
        return button;
    }

    private void configureTabButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(dp(54));
        button.setSingleLine(true);
    }

    private void configureMenuToggleButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(22);
        button.setMinWidth(0);
        button.setMinHeight(dp(44));
        button.setPadding(0, 0, 0, dp(2));
    }

    private EditText coordinateField(double value) {
        EditText field = compactEditText();
        field.setText(String.format(Locale.US, "%.5f", value));
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return field;
    }

    private void applyBostonLocation() {
        observerState = ObserverState.boston();
        latitudeField.setText(String.format(Locale.US, "%.5f", observerState.latitudeDegrees));
        longitudeField.setText(String.format(Locale.US, "%.5f", observerState.longitudeDegrees));
        updateObserverViews();
    }

    private void applyManualLocation() {
        logUserAction("tap apply-manual-location");
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(latitudeField.getText().toString().trim());
            longitude = Double.parseDouble(longitudeField.getText().toString().trim());
        } catch (NumberFormatException ex) {
            Logger.warn("manual location invalid number");
            setObserverMessage(getString(R.string.location_bad_input));
            return;
        }
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            Logger.warn("manual location out of range lat=" + latitude + " lon=" + longitude);
            setObserverMessage(getString(R.string.location_bad_input));
            return;
        }
        observerState = new ObserverState(latitude, longitude, ZoneId.systemDefault(), getString(R.string.manual_location_name));
        updateObserverViews();
        Logger.info("manual location applied " + observerLog());
    }

    private void requestGpsLocation() {
        logUserAction("tap use-gps-location");
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Logger.info("gps location permission requested");
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST
            );
            return;
        }
        useGpsLocation();
    }

    private void useGpsLocation() {
        setObserverMessage(getString(R.string.gps_waiting));
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            Logger.warn("gps location unavailable: no LocationManager");
            setObserverMessage(getString(R.string.gps_unavailable));
            return;
        }

        Location location = bestLastKnownLocation();
        if (location != null) {
            applyDeviceLocation(location);
            return;
        }

        String provider = firstEnabledProvider();
        if (provider == null) {
            Logger.warn("gps location unavailable: no enabled provider");
            setObserverMessage(getString(R.string.gps_unavailable));
            return;
        }

        try {
            Logger.info("gps single update requested provider=" + provider);
            locationManager.requestSingleUpdate(provider, new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    applyDeviceLocation(location);
                }

                @Override
                public void onProviderDisabled(String provider) {
                    Logger.warn("gps provider disabled provider=" + provider);
                    setObserverMessage(getString(R.string.gps_unavailable));
                }
            }, Looper.getMainLooper());
        } catch (SecurityException ex) {
            Logger.warn("gps permission denied", ex);
            setObserverMessage(getString(R.string.gps_permission_denied));
        } catch (IllegalArgumentException ex) {
            Logger.warn("gps request failed", ex);
            setObserverMessage(getString(R.string.gps_unavailable));
        }
    }

    private Location bestLastKnownLocation() {
        Location best = null;
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            try {
                if (!locationManager.isProviderEnabled(provider)) {
                    continue;
                }
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Try the next provider.
            }
        }
        return best;
    }

    private String firstEnabledProvider() {
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    return provider;
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next provider.
            }
        }
        return null;
    }

    private void applyDeviceLocation(Location location) {
        observerState = new ObserverState(
                location.getLatitude(),
                location.getLongitude(),
                ZoneId.systemDefault(),
                getString(R.string.gps_location_name)
        );
        latitudeField.setText(String.format(Locale.US, "%.5f", observerState.latitudeDegrees));
        longitudeField.setText(String.format(Locale.US, "%.5f", observerState.longitudeDegrees));
        updateObserverViews();
        Logger.info("gps location applied " + observerLog()
                + " accuracy=" + (location.hasAccuracy() ? location.getAccuracy() : -1.0f));
    }

    private void toggleNightMode() {
        logUserAction("toggle night-mode target=" + !nightModeEnabled);
        nightModeEnabled = !nightModeEnabled;
        applyNightModeWindow();
        setContentView(createContentView());
        updateUiState();
        updateObserverViews();
        updateTargetViews();
        updateGotoStatusViews();
        updateSafetyStatusViews();
        updateLogText();
        Logger.info("night-mode enabled=" + nightModeEnabled);
    }

    private void applyNightModeWindow() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = nightModeEnabled
                ? 0.08f
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(attributes);
    }

    private int pageBackgroundColor() {
        return nightModeEnabled ? Color.rgb(18, 4, 4) : Color.rgb(11, 18, 32);
    }

    private int cardBackgroundColor() {
        return nightModeEnabled ? Color.rgb(38, 7, 7) : Color.rgb(17, 24, 39);
    }

    private int titleTextColor() {
        return nightModeEnabled ? Color.rgb(255, 190, 190) : Color.rgb(226, 232, 240);
    }

    private int labelTextColor() {
        return nightModeEnabled ? Color.rgb(255, 175, 175) : Color.rgb(203, 213, 225);
    }

    private int bodyTextColor() {
        return nightModeEnabled ? Color.rgb(255, 145, 145) : Color.rgb(148, 163, 184);
    }

    private int mutedTextColor() {
        return nightModeEnabled ? Color.rgb(175, 80, 80) : Color.rgb(100, 116, 139);
    }

    private int selectedAccentColor() {
        return nightModeEnabled ? Color.rgb(255, 98, 98) : Color.rgb(56, 189, 248);
    }

    private LinearLayout card() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackgroundColor(cardBackgroundColor());
        return panel;
    }

    private LinearLayout modePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, 0);
        return panel;
    }

    private TextView titleText(int textRes, int sp) {
        TextView textView = new TextView(this);
        textView.setText(textRes);
        textView.setTextSize(sp);
        textView.setTextColor(titleTextColor());
        textView.setGravity(Gravity.START);
        return textView;
    }

    private TextView sectionTitle(int textRes) {
        TextView textView = titleText(textRes, 16);
        textView.setPadding(0, 0, 0, dp(4));
        return textView;
    }

    private LinearLayout sectionTitleWithHelp(int titleRes, int helpRes) {
        LinearLayout row = titleWithHelp(titleRes, helpRes, 16);
        row.setPadding(0, 0, 0, dp(4));
        return row;
    }

    private LinearLayout panelTitleWithHelp(int titleRes, int helpRes) {
        LinearLayout row = titleWithHelp(titleRes, helpRes, 15);
        row.setPadding(0, 0, 0, dp(6));
        return row;
    }

    private LinearLayout titleWithHelp(int titleRes, int helpRes, int sp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = titleText(titleRes, sp);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title, weightWrap(1f));

        Button help = new Button(this);
        help.setAllCaps(false);
        help.setText("?");
        help.setTextSize(13);
        help.setTypeface(Typeface.DEFAULT_BOLD);
        help.setTextColor(selectedAccentColor());
        help.setMinWidth(0);
        help.setMinHeight(0);
        help.setMinimumWidth(0);
        help.setMinimumHeight(0);
        help.setPadding(0, 0, 0, dp(1));
        help.setContentDescription(getString(R.string.help_button_content_description));
        help.setBackground(createHelpButtonBackground());
        help.setOnClickListener(v -> showHelpDialog(titleRes, helpRes));
        row.addView(help, squareParams(30));
        return row;
    }

    private void showHelpDialog(int titleRes, int helpRes) {
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(helpRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private TextView labelText(int textRes) {
        TextView textView = bodyText(textRes);
        textView.setTextColor(labelTextColor());
        textView.setGravity(Gravity.START);
        return textView;
    }

    private TextView bodyText(int textRes) {
        TextView textView = new TextView(this);
        textView.setText(textRes);
        textView.setTextSize(14);
        textView.setTextColor(bodyTextColor());
        textView.setGravity(Gravity.START);
        return textView;
    }

    private void compactSkyText(TextView textView) {
        textView.setTextSize(12);
        textView.setIncludeFontPadding(false);
    }

    private Button actionButton(int textRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(textRes);
        compactButton(button);
        return button;
    }

    private EditText compactEditText() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setMinHeight(dp(40));
        field.setMinimumHeight(dp(40));
        field.setPadding(dp(6), dp(3), dp(6), dp(3));
        field.setTextColor(titleTextColor());
        field.setHintTextColor(mutedTextColor());
        field.setBackground(createFieldBackground(true));
        return field;
    }

    private void compactButton(Button button) {
        button.setTextSize(14);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setTextColor(titleTextColor());
        button.setBackground(createActionButtonBackground(true));
    }

    private LinearLayout centeredRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private boolean isWideLayout() {
        Configuration config = getResources().getConfiguration();
        return config.screenWidthDp >= 840
                || (config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 600);
    }

    private FrameLayout.LayoutParams frameMatchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithTopMargin(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams matchParentWeight(float weight) {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight
        );
    }

    private FrameLayout.LayoutParams sideMenuParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(sideMenuExpanded ? SIDE_MENU_EXPANDED_WIDTH_DP : SIDE_MENU_COLLAPSED_SIZE_DP),
                sideMenuExpanded ? FrameLayout.LayoutParams.WRAP_CONTENT : dp(SIDE_MENU_COLLAPSED_SIZE_DP)
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = dp(SIDE_MENU_MARGIN_START_DP);
        params.topMargin = dp(SIDE_MENU_MARGIN_TOP_DP);
        return params;
    }

    private FrameLayout.LayoutParams floatingStopParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(56), dp(48));
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = dp(SIDE_MENU_MARGIN_START_DP);
        params.topMargin = dp(sideMenuExpanded ? expandedFloatingStopTopMarginDp() : FLOATING_STOP_COLLAPSED_TOP_MARGIN_DP);
        return params;
    }

    private int expandedFloatingStopTopMarginDp() {
        return SIDE_MENU_MARGIN_TOP_DP
                + SIDE_MENU_TOGGLE_HEIGHT_DP
                + SIDE_MENU_ITEM_TOP_MARGIN_DP
                + SIDE_MENU_ITEM_COUNT * (SIDE_MENU_ITEM_HEIGHT_DP + SIDE_MENU_ITEM_TOP_MARGIN_DP)
                + SIDE_MENU_VERSION_TOP_MARGIN_DP
                + SIDE_MENU_VERSION_HEIGHT_DP
                + SIDE_MENU_FLOATING_STOP_GAP_DP;
    }

    private LinearLayout.LayoutParams sideMenuToggleParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(SIDE_MENU_TOGGLE_HEIGHT_DP)
        );
    }

    private LinearLayout.LayoutParams sideMenuButtonParams(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(SIDE_MENU_ITEM_HEIGHT_DP)
        );
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams sideMenuVersionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(SIDE_MENU_VERSION_HEIGHT_DP)
        );
        params.topMargin = dp(SIDE_MENU_VERSION_TOP_MARGIN_DP);
        return params;
    }

    private LinearLayout.LayoutParams matchFixedHeight(int heightDp) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        );
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams weightWrapWithLeftMargin(float weight, int leftDp) {
        LinearLayout.LayoutParams params = weightWrap(weight);
        params.leftMargin = dp(leftDp);
        return params;
    }

    private LinearLayout.LayoutParams squareParams(int sizeDp) {
        return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
    }

    private LinearLayout.LayoutParams controlButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), dp(64));
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private GradientDrawable createDirectionButtonBackground(boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if (nightModeEnabled) {
            drawable.setColor(enabled ? Color.rgb(48, 10, 10) : Color.rgb(32, 7, 7));
            drawable.setStroke(dp(1), enabled ? Color.rgb(170, 58, 58) : Color.rgb(85, 30, 30));
        } else {
            drawable.setColor(enabled ? Color.rgb(30, 41, 59) : Color.rgb(15, 23, 42));
            drawable.setStroke(dp(1), enabled ? Color.rgb(71, 85, 105) : Color.rgb(30, 41, 59));
        }
        drawable.setCornerRadius(0);
        return drawable;
    }

    private GradientDrawable createStopButtonBackground() {
        return createStopButtonBackground(true);
    }

    private GradientDrawable createStopButtonBackground(boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(enabled ? Color.rgb(139, 0, 0) : Color.rgb(190, 90, 90));
        drawable.setStroke(dp(1), enabled ? Color.rgb(90, 0, 0) : Color.rgb(150, 70, 70));
        drawable.setCornerRadius(0);
        return drawable;
    }

    private GradientDrawable createHelpButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(nightModeEnabled ? Color.rgb(48, 10, 10) : Color.rgb(30, 41, 59));
        drawable.setStroke(dp(1), selectedAccentColor());
        return drawable;
    }

    private GradientDrawable createConnectBadgeBackground(boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if (nightModeEnabled) {
            drawable.setColor(enabled ? Color.rgb(48, 10, 10) : Color.rgb(32, 7, 7));
            drawable.setStroke(dp(1), enabled ? Color.rgb(255, 98, 98) : Color.rgb(85, 30, 30));
        } else {
            drawable.setColor(enabled ? Color.rgb(15, 23, 42) : Color.rgb(11, 18, 32));
            drawable.setStroke(dp(1), enabled ? selectedAccentColor() : Color.rgb(30, 41, 59));
        }
        drawable.setCornerRadius(dp(6));
        return drawable;
    }

    private GradientDrawable createConnectChipBackground(boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(enabled
                ? (nightModeEnabled ? Color.rgb(154, 28, 28) : Color.rgb(14, 116, 144))
                : Color.rgb(75, 85, 99));
        drawable.setCornerRadius(dp(3));
        return drawable;
    }

    private GradientDrawable createNightModeButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if (nightModeEnabled) {
            drawable.setColor(Color.rgb(95, 16, 16));
            drawable.setStroke(dp(1), Color.rgb(255, 98, 98));
        } else {
            drawable.setColor(Color.rgb(59, 130, 246));
            drawable.setStroke(dp(1), Color.rgb(147, 197, 253));
        }
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private GradientDrawable createFieldBackground(boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if (nightModeEnabled) {
            drawable.setColor(enabled ? Color.rgb(24, 6, 6) : Color.rgb(18, 4, 4));
            drawable.setStroke(dp(1), enabled ? Color.rgb(120, 45, 45) : Color.rgb(85, 30, 30));
        } else {
            drawable.setColor(enabled ? Color.rgb(15, 23, 42) : Color.rgb(11, 18, 32));
            drawable.setStroke(dp(1), enabled ? Color.rgb(51, 65, 85) : Color.rgb(30, 41, 59));
        }
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private GradientDrawable createActionButtonBackground(boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if (nightModeEnabled) {
            drawable.setColor(enabled ? Color.rgb(48, 10, 10) : Color.rgb(32, 7, 7));
            drawable.setStroke(dp(1), enabled ? Color.rgb(120, 45, 45) : Color.rgb(85, 30, 30));
        } else {
            drawable.setColor(enabled ? Color.rgb(30, 41, 59) : Color.rgb(15, 23, 42));
            drawable.setStroke(dp(1), enabled ? Color.rgb(71, 85, 105) : Color.rgb(30, 41, 59));
        }
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private GradientDrawable createRateButtonBackground(boolean selected, boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if (!enabled) {
            drawable.setColor(nightModeEnabled ? Color.rgb(32, 7, 7) : Color.rgb(15, 23, 42));
            drawable.setStroke(dp(1), nightModeEnabled ? Color.rgb(85, 30, 30) : Color.rgb(30, 41, 59));
        } else if (selected) {
            drawable.setColor(nightModeEnabled ? Color.rgb(74, 13, 13) : Color.rgb(8, 47, 73));
            drawable.setStroke(dp(2), selectedAccentColor());
        } else {
            drawable.setColor(cardBackgroundColor());
            drawable.setStroke(dp(1), nightModeEnabled ? Color.rgb(120, 45, 45) : Color.rgb(71, 85, 105));
        }
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private GradientDrawable createTabBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if (selected) {
            drawable.setColor(nightModeEnabled ? Color.rgb(95, 16, 16) : Color.rgb(14, 116, 144));
            drawable.setStroke(dp(1), selectedAccentColor());
        } else {
            drawable.setColor(Color.rgb(15, 23, 42));
            drawable.setStroke(dp(1), Color.rgb(51, 65, 85));
        }
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private GradientDrawable createMenuToggleBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.rgb(30, 41, 59));
        drawable.setStroke(dp(1), Color.rgb(71, 85, 105));
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private GradientDrawable createFloatingMenuBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.rgb(15, 23, 42));
        drawable.setStroke(dp(1), Color.rgb(30, 41, 59));
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        currentStatusMessage = text;
        if (statusText != null) {
            statusText.setText(text);
        }
        if (manualStatusText != null) {
            manualStatusText.setText(text);
        }
    }

    private void setGotoStatus(String text) {
        gotoStatusMessage = text;
        updateGotoStatusViews();
    }

    private void updateGotoStatusViews() {
        if (gotoStatusText != null) {
            gotoStatusText.setText(gotoStatusMessage == null ? getString(R.string.goto_status_idle) : gotoStatusMessage);
        }
    }

    private void setSafetyStatus(String text) {
        safetyStatusMessage = text;
        updateSafetyStatusViews();
    }

    private void updateSafetyStatusViews() {
        if (safetyStatusText != null) {
            safetyStatusText.setText(safetyStatusMessage == null ? getString(R.string.safety_status_idle) : safetyStatusMessage);
        }
    }

    private void appendLog(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("TX ") || trimmed.startsWith("RX ")) {
            // OnStepClient logs the real wire TX/RX after socket write/read completion.
            return;
        }
        if (trimmed.startsWith("DIAG ")) {
            Logger.diag(trimmed.substring(5));
        } else if (trimmed.startsWith("ERROR ")) {
            Logger.error(trimmed.substring(6));
        } else if (trimmed.startsWith("WARN ")) {
            Logger.warn(trimmed.substring(5));
        } else if (trimmed.startsWith("INFO ")) {
            Logger.info(trimmed.substring(5));
        } else if (trimmed.startsWith("CONNECT ") || trimmed.startsWith("DISCONNECT")) {
            Logger.info(trimmed);
        } else {
            Logger.info(trimmed);
        }
    }

    private void updateLogText() {
        StringBuilder builder = new StringBuilder();
        for (LogEntry entry : Logger.snapshot(1000)) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.formatted());
        }
        if (logText != null) {
            boolean shouldScroll = shouldAutoScrollLog();
            logText.setText(builder.length() == 0 ? getString(R.string.log_empty) : builder.toString());
            if (shouldScroll && logScrollView != null) {
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private boolean shouldAutoScrollLog() {
        if (logScrollView == null || logScrollView.getChildCount() == 0) {
            return true;
        }
        View content = logScrollView.getChildAt(0);
        int distanceFromBottom = content.getBottom() - (logScrollView.getHeight() + logScrollView.getScrollY());
        return distanceFromBottom <= dp(16);
    }

    private void updateUiState() {
        hostField.setEnabled(!connected && !busy);
        portField.setEnabled(!connected && !busy);
        connectTrigger.setEnabled(!connected && !busy);
        connectTrigger.setBackground(createConnectBadgeBackground(!connected && !busy));
        disconnectButton.setEnabled(connected && !busy);
        connectionForm.setVisibility(connected ? View.GONE : View.VISIBLE);
        connectTrigger.setVisibility(connected ? View.GONE : View.VISIBLE);
        disconnectButton.setVisibility(connected ? View.VISIBLE : View.GONE);
        updateManualRateControl();
        if (gotoButton != null) {
            gotoButton.setEnabled(!busy);
            gotoButton.setText(connected ? R.string.sky_goto_target : R.string.sky_find_target);
        }
        if (skyCancelGotoButton != null) {
            skyCancelGotoButton.setEnabled(connected && !busy && gotoInProgress);
        }
        if (safetyCancelGotoButton != null) {
            safetyCancelGotoButton.setEnabled(connected && !busy && gotoInProgress);
        }
        if (gotoStatusRefreshButton != null) {
            gotoStatusRefreshButton.setEnabled(connected && !busy);
        }
        if (floatingStopButton != null) {
            floatingStopButton.setEnabled(connected);
            floatingStopButton.setVisibility(connected ? View.VISIBLE : View.GONE);
            floatingStopButton.setAlpha(1.0f);
        }
        if (emergencyStopButton != null) {
            emergencyStopButton.setEnabled(connected);
            emergencyStopButton.setBackground(createStopButtonBackground(connected));
            emergencyStopButton.setTextColor(Color.WHITE);
        }
        if (parkButton != null) {
            parkButton.setEnabled(connected && !busy);
        }
        if (unparkButton != null) {
            unparkButton.setEnabled(connected && !busy);
        }
        if (nightModeButton != null) {
            nightModeButton.setText(nightModeEnabled ? R.string.night_mode_off : R.string.night_mode_on);
            nightModeButton.setTextColor(Color.WHITE);
            nightModeButton.setBackground(createNightModeButtonBackground());
        }
        if (syncMountButton != null) {
            syncMountButton.setEnabled(connected && !busy);
        }
        if (trackingToggleButton != null) {
            trackingToggleButton.setEnabled(connected && !busy);
        }
        if (gotoHomeButton != null) {
            gotoHomeButton.setEnabled(connected && !busy && !parked && !gotoInProgress);
        }
        if (setHomeButton != null) {
            setHomeButton.setEnabled(connected && !busy);
        }
        setTrackingRateButtonEnabled(trackingSiderealButton, !busy);
        setTrackingRateButtonEnabled(trackingLunarButton, !busy);
        setTrackingRateButtonEnabled(trackingSolarButton, !busy);
        if (calibrationSuggestButton != null) {
            calibrationSuggestButton.setEnabled(skyChartView != null);
        }
        if (calibrationShowButton != null) {
            calibrationShowButton.setEnabled(skyChartView != null && !busy);
            updateCalibrationTargetActionButton();
        }
        if (quickSelectButton != null) {
            quickSelectButton.setEnabled(!busy);
        }
        if (quickSyncButton != null) {
            quickSyncButton.setEnabled(connected && !busy);
        }
        boolean canStartAlignment = connected && !busy && alignmentSession == null;
        if (calibrationModeSpinner != null) {
            calibrationModeSpinner.setEnabled(!busy && alignmentSession == null);
        }
        if (alignStartButton != null) {
            alignStartButton.setEnabled(canStartAlignment && selectedCalibrationMode.starCount > 0);
        }
        boolean alignmentActive = connected && !busy && alignmentSession != null && !alignmentSession.isComplete();
        if (alignSelectButton != null) {
            alignSelectButton.setEnabled(alignmentActive);
        }
        if (alignGotoButton != null) {
            alignGotoButton.setEnabled(alignmentActive);
        }
        if (alignAcceptButton != null) {
            alignAcceptButton.setEnabled(alignmentActive);
        }
        if (alignSaveButton != null) {
            alignSaveButton.setEnabled(connected && !busy && alignmentSession != null && alignmentSession.isComplete());
        }
        if (alignCancelButton != null) {
            alignCancelButton.setEnabled(alignmentSession != null && !busy);
        }
        if (refineGotoButton != null) {
            refineGotoButton.setEnabled(!isAltAzMountMode() && connected && !busy && hasPolarRefineAlignmentModel());
        }
        if (refinePaButton != null) {
            refinePaButton.setEnabled(!isAltAzMountMode()
                    && connected && !busy && hasPolarRefineAlignmentModel() && polarRefineSyncedTarget != null);
        }

        boolean controlsEnabled = connected && !busy;
        setDirectionButtonEnabled(northButton, controlsEnabled);
        setDirectionButtonEnabled(northEastButton, controlsEnabled);
        setDirectionButtonEnabled(northWestButton, controlsEnabled);
        setDirectionButtonEnabled(southButton, controlsEnabled);
        setDirectionButtonEnabled(southEastButton, controlsEnabled);
        setDirectionButtonEnabled(southWestButton, controlsEnabled);
        setDirectionButtonEnabled(eastButton, controlsEnabled);
        setDirectionButtonEnabled(westButton, controlsEnabled);
        if (stopButton != null) {
            stopButton.setEnabled(controlsEnabled);
            stopButton.setBackground(createStopButtonBackground(controlsEnabled));
            stopButton.setTextColor(Color.WHITE);
        }
        updateTrackingViews();
        updateFirmwareSettingsViews();
    }

    private void updateObserverViews() {
        updateSkyTime();
        if (observerStatusText != null) {
            observerStatusText.setText(getString(
                    R.string.observer_status,
                    observerState.locationName,
                    observerState.formatCoordinates()
            ));
        }
        if (skySummaryText != null && skyChartView != null) {
            skySummaryText.setText(skyChartView.summary());
        }
    }

    private void updateSkyTime() {
        Instant now = Instant.now();
        if (timeStatusText != null) {
            timeStatusText.setText(getString(R.string.time_status, observerState.formatTime(now)));
        }
        if (skyChartView != null) {
            skyChartView.setObserver(observerState, now);
        }
        updateObservingAlert();
    }

    private void setObserverMessage(String message) {
        if (observerStatusText != null) {
            observerStatusText.setText(message);
        }
    }

    private void acquireWifiLock() {
        if (wifiLock == null) {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager != null) {
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OnStepControllerWifi");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }
    }

    private void releaseWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    private SocketFactory stableWifiSocketFactory() {
        Network wifiNetwork = findWifiNetwork();
        if (wifiNetwork != null && connectivityManager != null) {
            connectivityManager.bindProcessToNetwork(wifiNetwork);
            boundWifiNetwork = wifiNetwork;
            return wifiNetwork.getSocketFactory();
        }
        releaseWifiBinding();
        return SocketFactory.getDefault();
    }

    private Network findWifiNetwork() {
        if (connectivityManager == null) {
            return null;
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (isWifiNetwork(activeNetwork)) {
            return activeNetwork;
        }
        for (Network network : connectivityManager.getAllNetworks()) {
            if (isWifiNetwork(network)) {
                return network;
            }
        }
        return null;
    }

    private boolean isWifiNetwork(Network network) {
        if (network == null || connectivityManager == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void releaseWifiBinding() {
        if (connectivityManager != null && boundWifiNetwork != null) {
            connectivityManager.bindProcessToNetwork(null);
            boundWifiNetwork = null;
        }
    }

    private boolean isConnectionGenerationCurrent(int generation) {
        return generation == connectionGeneration.get();
    }

    private void markTransportFault() {
        connectionGeneration.incrementAndGet();
        logStateSnapshot("transport-fault");
    }

    private void logUserAction(String action) {
        Logger.user(action);
    }

    private void logStateSnapshot(String reason) {
        Logger.info("state " + reason
                + " connected=" + connected
                + " busy=" + busy
                + " gotoInProgress=" + gotoInProgress
                + " homeFindInProgress=" + homeFindInProgress
                + " parked=" + parked
                + " trackingEnabled=" + trackingEnabled
                + " trackingMode=" + (trackingUsingDualAxis ? "dual" : "single")
                + " rate=" + selectedTrackingRate.name()
                + " savedAlignmentStars=" + savedAlignmentStarCount
                + " firmware=" + selectedFirmwareMode.name()
                + " mount=" + selectedMountMode.name()
                + " activeDirection=" + (activeDirection == null ? "none" : activeDirection.name())
                + " alignment=" + alignmentStateSummary());
    }

    private String alignmentStateSummary() {
        if (alignmentSession == null) {
            return "none";
        }
        return alignmentSession.acceptedStars + "/" + alignmentSession.totalStars
                + (alignmentSession.currentTarget == null ? "" : " target=" + alignmentSession.currentTarget.label);
    }

    private String targetLog(SkyChartView.Target target) {
        if (target == null) {
            return "target=none";
        }
        return "target=\"" + target.label + "\" RA=" + formatRightAscensionDisplay(target.raHours)
                + " Dec=" + formatDeclinationDisplay(target.decDegrees);
    }

    private String observerLog() {
        return String.format(
                Locale.US,
                "observer=\"%s\" lat=%.5f lon=%.5f zone=%s",
                observerState.locationName,
                observerState.latitudeDegrees,
                observerState.longitudeDegrees,
                observerState.zoneId
        );
    }

    private void updatePageTabs(Page selectedPage) {
        currentPage = selectedPage;
        if (manualPage != null) {
            manualPage.setVisibility(selectedPage == Page.MANUAL ? View.VISIBLE : View.GONE);
        }
        if (skyPage != null) {
            skyPage.setVisibility(selectedPage == Page.SKY ? View.VISIBLE : View.GONE);
        }
        if (settingsPage != null) {
            settingsPage.setVisibility(selectedPage == Page.SETTINGS ? View.VISIBLE : View.GONE);
        }
        if (connectionSyncPage != null) {
            connectionSyncPage.setVisibility(selectedPage == Page.CONNECTION_SYNC ? View.VISIBLE : View.GONE);
        }
        styleTabButton(manualTabButton, selectedPage == Page.MANUAL);
        styleTabButton(skyTabButton, selectedPage == Page.SKY);
        styleTabButton(settingsTabButton, selectedPage == Page.SETTINGS);
        styleTabButton(connectionSyncTabButton, selectedPage == Page.CONNECTION_SYNC);
    }

    private void selectPageFromMenu(Page selectedPage) {
        logUserAction("select page " + selectedPage.name());
        if (selectingCalibrationTargetFromSky && selectedPage != Page.SKY) {
            selectingCalibrationTargetFromSky = false;
            if (calibrationTargetConfirmDialog != null && calibrationTargetConfirmDialog.isShowing()) {
                calibrationTargetConfirmDialog.dismiss();
            }
        }
        updatePageTabs(selectedPage);
        setSideMenuExpanded(false);
    }

    private void setSideMenuExpanded(boolean expanded) {
        sideMenuExpanded = expanded;
        if (sideMenu == null) {
            return;
        }

        sideMenu.setLayoutParams(sideMenuParams());
        int padding = expanded ? 8 : 4;
        sideMenu.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        sideMenu.setBackground(expanded ? createFloatingMenuBackground() : null);

        int menuItemVisibility = expanded ? View.VISIBLE : View.GONE;
        if (settingsTabButton != null) {
            settingsTabButton.setVisibility(menuItemVisibility);
        }
        if (connectionSyncTabButton != null) {
            connectionSyncTabButton.setVisibility(menuItemVisibility);
        }
        if (manualTabButton != null) {
            manualTabButton.setVisibility(menuItemVisibility);
        }
        if (skyTabButton != null) {
            skyTabButton.setVisibility(menuItemVisibility);
        }
        if (sideMenuVersionText != null) {
            sideMenuVersionText.setVisibility(menuItemVisibility);
        }
        if (sideMenuToggleButton != null) {
            sideMenuToggleButton.setText(expanded ? "\u00d7" : "\u2630");
            sideMenuToggleButton.setTextColor(Color.rgb(226, 232, 240));
            sideMenuToggleButton.setBackground(createMenuToggleBackground());
        }
        if (floatingStopButton != null) {
            floatingStopButton.setLayoutParams(floatingStopParams());
        }
    }

    private void styleTabButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setTextColor(selected ? Color.WHITE : Color.rgb(226, 232, 240));
        button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setBackground(createTabBackground(selected));
    }

    private void updateManualRateControl() {
        if (manualRateSpinner != null) {
            manualRateSpinner.setEnabled(!busy);
        }
    }

    private void updateTrackingViews() {
        styleTrackingRateButton(trackingSiderealButton, TrackingRate.SIDEREAL);
        styleTrackingRateButton(trackingLunarButton, TrackingRate.LUNAR);
        styleTrackingRateButton(trackingSolarButton, TrackingRate.SOLAR);

        if (trackingToggleButton != null) {
            trackingToggleButton.setText(trackingEnabled ? R.string.tracking_stop : R.string.tracking_start);
        }
        if (trackingStatusText != null) {
            if (trackingEnabled) {
                trackingStatusText.setText(getString(
                        R.string.tracking_status_on,
                        getString(selectedTrackingRate.labelRes),
                        trackingModeLabel(trackingUsingDualAxis)
                ));
            } else {
                trackingStatusText.setText(getString(
                        R.string.tracking_status_off_with_rate,
                        getString(selectedTrackingRate.labelRes),
                        trackingModeLabel(shouldStartDualAxisTracking())
                ));
            }
        }
    }

    private void styleTrackingRateButton(Button button, TrackingRate rate) {
        if (button == null) {
            return;
        }
        boolean enabled = !busy;
        boolean selected = selectedTrackingRate == rate;
        button.setTextColor(selected ? selectedAccentColor() : labelTextColor());
        button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setBackground(createRateButtonBackground(selected, enabled));
    }

    private void setDirectionButtonEnabled(Button button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setTextColor(enabled ? titleTextColor() : mutedTextColor());
        button.setBackground(createDirectionButtonBackground(enabled));
    }

    private void setTrackingRateButtonEnabled(Button button, boolean enabled) {
        if (button != null) {
            button.setEnabled(enabled);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ex) {
            return "0.2.1";
        }
    }

    private static final class AlignmentSession {
        final int totalStars;
        final List<SkyChartView.Target> acceptedTargets = new ArrayList<>();
        final List<String> acceptedLabels = new ArrayList<>();
        int acceptedStars;
        SkyChartView.Target currentTarget;

        AlignmentSession(int totalStars) {
            this.totalStars = totalStars;
        }

        int currentStarNumber() {
            return Math.min(totalStars, acceptedStars + 1);
        }

        boolean isComplete() {
            return acceptedStars >= totalStars;
        }
    }

    private static final class ConnectionAttempt {
        final int port;
        final String handshake;

        ConnectionAttempt(int port, String handshake) {
            this.port = port;
            this.handshake = handshake;
        }
    }

    private static final class HorizontalCoordinates {
        final double altitudeDegrees;
        final double azimuthDegrees;

        HorizontalCoordinates(double altitudeDegrees, double azimuthDegrees) {
            this.altitudeDegrees = altitudeDegrees;
            this.azimuthDegrees = azimuthDegrees;
        }
    }

    private static final class EquatorialPoint {
        final double raHours;
        final double decDegrees;

        EquatorialPoint(double raHours, double decDegrees) {
            this.raHours = raHours;
            this.decDegrees = decDegrees;
        }
    }

    private static final class AlignmentDiagnosticSnapshot {
        final String pierSide;

        AlignmentDiagnosticSnapshot(String pierSide) {
            this.pierSide = pierSide == null ? "" : pierSide.trim();
        }
    }

    private static final class MountCommand {
        final String command;
        final boolean expectReply;

        private MountCommand(String command, boolean expectReply) {
            this.command = command;
            this.expectReply = expectReply;
        }

        static MountCommand withReply(String command) {
            return new MountCommand(command, true);
        }

        static MountCommand noReply(String command) {
            return new MountCommand(command, false);
        }
    }

    private static final class CommandRejectedException extends Exception {
        final String command;
        final String reply;

        CommandRejectedException(String command, String reply) {
            super(command + " rejected: " + reply);
            this.command = command;
            this.reply = reply;
        }
    }

    private enum OnStepCommand {
        RATE_GUIDE(":RG#"),
        RATE_CENTER(":RC#"),
        RATE_FIND(":RM#"),
        RATE_FAST(":R7#"),
        RATE_HALF_MAX(":R8#"),
        RATE_MAX(":R9#"),
        MOVE_NORTH(":Mn#"),
        MOVE_SOUTH(":Ms#"),
        MOVE_EAST(":Me#"),
        MOVE_WEST(":Mw#"),
        SYNC_CURRENT_TARGET(":CM#"),
        ALIGN_ABORT(":A0#"),
        ALIGN_TWO(":A2#"),
        ALIGN_THREE(":A3#"),
        ALIGN_WRITE(":AW#"),
        TRACK_SIDEREAL(":TQ#"),
        TRACK_LUNAR(":TL#"),
        TRACK_SOLAR(":TS#"),
        TRACK_ENABLE(":Te#"),
        TRACK_DISABLE(":Td#"),
        TRACK_FULL_COMPENSATION(":To#"),
        TRACK_DUAL_AXIS(":T2#"),
        REFINE_POLAR_ALIGNMENT(":MP#"),
        HOME_CALIBRATE(":hC#"),
        HOME_FIND(":hF#"),
        PARK(":hP#"),
        UNPARK(":hR#"),
        GOTO_STATUS(":D#"),
        STOP_NORTH(":Qn#"),
        STOP_SOUTH(":Qs#"),
        STOP_EAST(":Qe#"),
        STOP_WEST(":Qw#"),
        STOP_ALL(":Q#");

        private final String command;

        OnStepCommand(String command) {
            this.command = command;
        }
    }

    private enum ManualRate {
        GUIDE(R.string.rate_guide, OnStepCommand.RATE_GUIDE.command),
        CENTER(R.string.rate_center, OnStepCommand.RATE_CENTER.command),
        FIND(R.string.rate_find, OnStepCommand.RATE_FIND.command),
        FAST(R.string.rate_fast, OnStepCommand.RATE_FAST.command),
        HALF_MAX(R.string.rate_half_max, OnStepCommand.RATE_HALF_MAX.command),
        MAX(R.string.rate_max, OnStepCommand.RATE_MAX.command);

        private final int labelRes;
        private final String command;

        ManualRate(int labelRes, String command) {
            this.labelRes = labelRes;
            this.command = command;
        }
    }

    private enum TrackingRate {
        SIDEREAL(R.string.tracking_rate_sidereal, OnStepCommand.TRACK_SIDEREAL.command),
        LUNAR(R.string.tracking_rate_lunar, OnStepCommand.TRACK_LUNAR.command),
        SOLAR(R.string.tracking_rate_solar, OnStepCommand.TRACK_SOLAR.command);

        private final int labelRes;
        private final String command;

        TrackingRate(int labelRes, String command) {
            this.labelRes = labelRes;
            this.command = command;
        }
    }

    private enum CalibrationMode {
        QUICK_SYNC(R.string.calibration_mode_quick_sync, 0),
        TWO_STAR(R.string.calibration_mode_two_star, 2),
        THREE_STAR(R.string.calibration_mode_three_star, 3),
        REFINE_POLAR(R.string.calibration_mode_refine_polar, 0);

        private final int labelRes;
        private final int starCount;

        CalibrationMode(int labelRes, int starCount) {
            this.labelRes = labelRes;
            this.starCount = starCount;
        }

        private boolean isStarAlignment() {
            return starCount > 0;
        }
    }

    private enum FirmwareMode {
        ONSTEP(R.string.firmware_mode_onstep),
        ONSTEPX(R.string.firmware_mode_onstepx);

        private final int labelRes;

        FirmwareMode(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    private enum MountMode {
        EQUATORIAL(R.string.mount_mode_equatorial, 1),
        ALTAZ(R.string.mount_mode_altaz, 3);

        private final int labelRes;
        private final int onStepXCode;

        MountMode(int labelRes, int onStepXCode) {
            this.labelRes = labelRes;
            this.onStepXCode = onStepXCode;
        }
    }

    private enum Page {
        SETTINGS,
        CONNECTION_SYNC,
        MANUAL,
        SKY
    }

    private enum Direction {
        NORTH(
                R.string.direction_north,
                new OnStepCommand[]{OnStepCommand.MOVE_NORTH},
                new OnStepCommand[]{OnStepCommand.STOP_NORTH}
        ),
        NORTH_EAST(
                R.string.direction_north_east,
                new OnStepCommand[]{OnStepCommand.MOVE_NORTH, OnStepCommand.MOVE_EAST},
                new OnStepCommand[]{OnStepCommand.STOP_NORTH, OnStepCommand.STOP_EAST}
        ),
        EAST(
                R.string.direction_east,
                new OnStepCommand[]{OnStepCommand.MOVE_EAST},
                new OnStepCommand[]{OnStepCommand.STOP_EAST}
        ),
        SOUTH_EAST(
                R.string.direction_south_east,
                new OnStepCommand[]{OnStepCommand.MOVE_SOUTH, OnStepCommand.MOVE_EAST},
                new OnStepCommand[]{OnStepCommand.STOP_SOUTH, OnStepCommand.STOP_EAST}
        ),
        SOUTH(
                R.string.direction_south,
                new OnStepCommand[]{OnStepCommand.MOVE_SOUTH},
                new OnStepCommand[]{OnStepCommand.STOP_SOUTH}
        ),
        SOUTH_WEST(
                R.string.direction_south_west,
                new OnStepCommand[]{OnStepCommand.MOVE_SOUTH, OnStepCommand.MOVE_WEST},
                new OnStepCommand[]{OnStepCommand.STOP_SOUTH, OnStepCommand.STOP_WEST}
        ),
        WEST(
                R.string.direction_west,
                new OnStepCommand[]{OnStepCommand.MOVE_WEST},
                new OnStepCommand[]{OnStepCommand.STOP_WEST}
        ),
        NORTH_WEST(
                R.string.direction_north_west,
                new OnStepCommand[]{OnStepCommand.MOVE_NORTH, OnStepCommand.MOVE_WEST},
                new OnStepCommand[]{OnStepCommand.STOP_NORTH, OnStepCommand.STOP_WEST}
        );

        private final int labelRes;
        private final OnStepCommand[] moveCommands;
        private final OnStepCommand[] stopCommands;

        Direction(int labelRes, OnStepCommand[] moveCommands, OnStepCommand[] stopCommands) {
            this.labelRes = labelRes;
            this.moveCommands = moveCommands;
            this.stopCommands = stopCommands;
        }

        private String label(Activity activity) {
            return activity.getString(labelRes);
        }
    }
}
