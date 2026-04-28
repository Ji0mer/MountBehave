package com.example.onstepcontroller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
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
import java.util.ArrayDeque;
import java.util.Deque;
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
    private static final String PREF_HOME_HAS_POSITION = "home_has_position";
    private static final String PREF_HOME_VALID = "home_valid";
    private static final String PREF_HOME_REFERENCE_RA = "home_reference_ra";
    private static final String PREF_HOME_REFERENCE_EPOCH_MS = "home_reference_epoch_ms";
    private static final String PREF_HOME_HOUR_ANGLE = "home_hour_angle";
    private static final String PREF_HOME_DEC_DEGREES = "home_dec_degrees";
    private static final int LOCATION_PERMISSION_REQUEST = 24;
    private static final long CONNECTION_POLL_INTERVAL_MS = 5_000L;
    private static final Pattern COORDINATE_TARGET_PATTERN = Pattern.compile(
            "^\\s*(?:RA\\s*)?([0-9]{1,2}(?::[0-9]{1,2}(?::[0-9]{1,2}(?:\\.\\d+)?)?)?|[0-9]+(?:\\.\\d+)?)\\s*[, ]+\\s*(?:DEC\\s*)?([+-]?[0-9]{1,2}(?:(?::|\\*|°)[0-9]{1,2}(?:(?::|')?[0-9]{1,2}(?:\\.\\d+)?)?)?|[+-]?[0-9]+(?:\\.\\d+)?)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final OnStepClient client = new OnStepClient();
    private final Deque<String> logLines = new ArrayDeque<>();
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
    private Button sideMenuToggleButton;
    private Button floatingStopButton;
    private LinearLayout sideMenu;
    private LinearLayout manualPage;
    private LinearLayout skyPage;
    private LinearLayout settingsPage;
    private EditText hostField;
    private EditText portField;
    private LinearLayout connectionForm;
    private Button connectButton;
    private Button disconnectButton;
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
    private TextView homeStatusText;
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
    private boolean hasHomePosition;
    private boolean homePositionValid;
    private double homeReferenceRaHours;
    private double homeHourAngleHours;
    private double homeDecDegrees;
    private Instant homeReferenceInstant;
    private boolean trackingEnabled;
    private boolean hasThreeStarTrackingModel;
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
    private ManualRate selectedManualRate = ManualRate.CENTER;
    private CalibrationMode selectedCalibrationMode = CalibrationMode.QUICK_SYNC;
    private Page currentPage = Page.SETTINGS;
    private volatile AlignmentSession alignmentSession;
    private int suggestedCalibrationIndex;
    private SmallBodyCatalog smallBodyCatalog;
    private TextView smallBodyStatusText;
    private Button smallBodyDownloadAsteroidsButton;
    private Button smallBodyDownloadCometsButton;
    private Button smallBodyClearUserButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectivityManager = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyNightModeWindow();
        smallBodyCatalog = new SmallBodyCatalog(getFilesDir());
        setContentView(createContentView());
        updateUiState();
        updateObserverViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        uiHandler.removeCallbacks(skyClockRunnable);
        if (connected && activeDirection != null) {
            activeDirection = null;
            enqueueStop(getString(R.string.log_auto_stop_background));
        }
        releaseWifiLock();
    }

    @Override
    protected void onDestroy() {
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
            useGpsLocation();
        } else {
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

        settingsPage = createSettingsPage();
        settingsPage.setVisibility(View.GONE);
        root.addView(settingsPage, matchWrap());

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
        sideMenu.addView(settingsTabButton, sideMenuButtonParams(10));

        manualTabButton = new Button(this);
        configureTabButton(manualTabButton);
        manualTabButton.setText(R.string.tab_manual);
        manualTabButton.setOnClickListener(v -> selectPageFromMenu(Page.MANUAL));
        sideMenu.addView(manualTabButton, sideMenuButtonParams(10));

        skyTabButton = new Button(this);
        configureTabButton(skyTabButton);
        skyTabButton.setText(R.string.tab_sky);
        skyTabButton.setOnClickListener(v -> selectPageFromMenu(Page.SKY));
        sideMenu.addView(skyTabButton, sideMenuButtonParams(10));

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
        skySummaryText.setPadding(0, 0, 0, dp(6));
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
        targetStatusText.setPadding(0, 0, 0, 0);
        panel.addView(targetStatusText, matchWrap());

        mountPointingText = bodyText(R.string.mount_pointing_default);
        mountPointingText.setPadding(0, dp(3), 0, 0);
        if (hasCurrentMountPosition) {
            mountPointingText.setText(getString(
                    R.string.mount_pointing_status,
                    formatRightAscensionDisplay(currentMountRaHours),
                    formatDeclinationDisplay(currentMountDecDegrees)
            ));
        }
        panel.addView(mountPointingText, matchWrap());

        gotoStatusText = bodyText(R.string.goto_status_idle);
        gotoStatusText.setPadding(0, dp(3), 0, 0);
        if (gotoStatusMessage != null) {
            gotoStatusText.setText(gotoStatusMessage);
        }
        panel.addView(gotoStatusText, matchWrap());

        observingAlertText = bodyText(R.string.observing_alert_no_target);
        observingAlertText.setPadding(0, dp(3), 0, 0);
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

        page.addView(sectionTitleWithHelp(R.string.calibration_section, R.string.calibration_intro), matchWrap());

        LinearLayout targetPanel = card();
        targetPanel.addView(labelText(R.string.calibration_mode_label), matchWrap());
        calibrationModeSpinner = new Spinner(this);
        List<String> modeLabels = new ArrayList<>();
        for (CalibrationMode mode : CalibrationMode.values()) {
            modeLabels.add(getString(mode.labelRes));
        }
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                modeLabels
        );
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        calibrationModeSpinner.setAdapter(modeAdapter);
        calibrationModeSpinner.setSelection(selectedCalibrationMode.ordinal());
        calibrationModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedCalibrationMode = CalibrationMode.values()[position];
                updateCalibrationModeViews();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current mode.
            }
        });
        targetPanel.addView(calibrationModeSpinner, matchWrap());

        targetPanel.addView(labelText(R.string.calibration_target_label), matchWrap());
        calibrationTargetField = new EditText(this);
        calibrationTargetField.setSingleLine(true);
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
        targetPanel.addView(calibrationTargetField, matchWrap());

        LinearLayout targetActions = new LinearLayout(this);
        targetActions.setOrientation(LinearLayout.HORIZONTAL);
        targetActions.setGravity(Gravity.CENTER_VERTICAL);
        targetActions.setPadding(0, dp(8), 0, 0);

        calibrationSuggestButton = actionButton(R.string.calibration_suggest_star);
        calibrationSuggestButton.setOnClickListener(v -> fillSuggestedCalibrationTarget());
        targetActions.addView(calibrationSuggestButton, weightWrap(1f));

        calibrationShowButton = actionButton(R.string.calibration_show_in_sky);
        calibrationShowButton.setOnClickListener(v -> handleCalibrationTargetAction());
        targetActions.addView(calibrationShowButton, weightWrapWithLeftMargin(1f, 8));
        updateCalibrationTargetActionButton();

        targetPanel.addView(targetActions, matchWrap());

        calibrationStatusText = bodyText(R.string.calibration_status_idle);
        calibrationStatusText.setPadding(0, dp(8), 0, 0);
        targetPanel.addView(calibrationStatusText, matchWrap());
        page.addView(targetPanel, matchWrap());

        page.addView(sectionTitle(R.string.calibration_mode_settings), matchWrapWithTopMargin(8));

        quickCalibrationPanel = card();
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
        page.addView(quickCalibrationPanel, matchWrap());

        alignCalibrationPanel = card();
        alignCalibrationPanel.addView(panelTitleWithHelp(
                R.string.calibration_align_section,
                R.string.calibration_align_intro
        ), matchWrap());

        alignStartButton = actionButton(R.string.calibration_align_start);
        alignStartButton.setOnClickListener(v -> {
            if (selectedCalibrationMode.starCount > 0) {
                startAlignment(selectedCalibrationMode.starCount);
            }
        });
        alignCalibrationPanel.addView(alignStartButton, matchWrap());

        calibrationStepText = bodyText(R.string.calibration_align_idle);
        calibrationStepText.setPadding(0, dp(8), 0, dp(8));
        alignCalibrationPanel.addView(calibrationStepText, matchWrap());

        alignmentCurrentText = bodyText(R.string.calibration_align_current_none);
        alignmentCurrentText.setPadding(0, 0, 0, dp(6));
        alignCalibrationPanel.addView(alignmentCurrentText, matchWrap());

        alignmentAcceptedText = bodyText(R.string.calibration_align_accepted_none);
        alignmentAcceptedText.setPadding(0, 0, 0, dp(8));
        alignCalibrationPanel.addView(alignmentAcceptedText, matchWrap());

        LinearLayout alignActionsOne = new LinearLayout(this);
        alignActionsOne.setOrientation(LinearLayout.HORIZONTAL);
        alignActionsOne.setGravity(Gravity.CENTER_VERTICAL);

        alignSelectButton = actionButton(R.string.calibration_align_select_current);
        alignSelectButton.setOnClickListener(v -> selectAlignmentTargetOnly());
        alignActionsOne.addView(alignSelectButton, weightWrap(1f));

        alignGotoButton = actionButton(R.string.calibration_align_goto);
        alignGotoButton.setOnClickListener(v -> gotoAlignmentTarget());
        alignActionsOne.addView(alignGotoButton, weightWrapWithLeftMargin(1f, 8));

        alignCalibrationPanel.addView(alignActionsOne, matchWrap());

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
        page.addView(alignCalibrationPanel, matchWrap());

        refineCalibrationPanel = card();
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

        page.addView(refineCalibrationPanel, matchWrap());
        updateCalibrationModeViews();
        return page;
    }

    private LinearLayout createSettingsPage() {
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
            addMoreSettingsGroup(right);
            columns.addView(right, weightWrapWithLeftMargin(1f, 12));

            page.addView(columns, matchWrap());
        } else {
            page.addView(sectionTitle(R.string.connection_section), matchWrap());
            page.addView(createConnectionPanel(), matchWrap());

            page.addView(sectionTitle(R.string.observer_section), matchWrapWithTopMargin(8));
            page.addView(createObserverPanel(), matchWrap());

            page.addView(sectionTitleWithHelp(R.string.tracking_section, R.string.tracking_intro), matchWrapWithTopMargin(8));
            page.addView(createTrackingPanel(), matchWrap());

            addMoreSettingsGroup(page);
        }

        return page;
    }

    private void addMoreSettingsGroup(LinearLayout parent) {
        Button toggle = new Button(this);
        toggle.setAllCaps(false);
        compactButton(toggle);
        toggle.setText(R.string.more_settings_collapsed);
        LinearLayout.LayoutParams toggleParams = matchWrap();
        toggleParams.topMargin = dp(8);
        parent.addView(toggle, toggleParams);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setVisibility(View.GONE);
        container.addView(sectionTitle(R.string.command_log_section), matchWrapWithTopMargin(8));
        container.addView(createCommandLogPanel(), matchWrap());
        container.addView(sectionTitleWithHelp(R.string.safety_section, R.string.safety_intro), matchWrapWithTopMargin(8));
        container.addView(createSafetyPanel(), matchWrap());
        container.addView(sectionTitleWithHelp(R.string.small_bodies_section, R.string.small_bodies_intro), matchWrapWithTopMargin(8));
        container.addView(createSmallBodiesPanel(), matchWrap());
        parent.addView(container, matchWrap());

        toggle.setOnClickListener(v -> {
            boolean expanded = container.getVisibility() == View.VISIBLE;
            container.setVisibility(expanded ? View.GONE : View.VISIBLE);
            toggle.setText(expanded ? R.string.more_settings_collapsed : R.string.more_settings_expanded);
        });
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

        safetyCancelGotoButton = actionButton(R.string.goto_cancel);
        safetyCancelGotoButton.setOnClickListener(v -> cancelGoto());
        stopActions.addView(safetyCancelGotoButton, weightWrapWithLeftMargin(1f, 8));
        panel.addView(stopActions, matchWrap());

        LinearLayout gotoActions = new LinearLayout(this);
        gotoActions.setOrientation(LinearLayout.HORIZONTAL);
        gotoActions.setGravity(Gravity.CENTER_VERTICAL);
        gotoActions.setPadding(0, dp(6), 0, 0);

        gotoStatusRefreshButton = actionButton(R.string.goto_refresh_status);
        gotoStatusRefreshButton.setOnClickListener(v -> refreshGotoStatus());
        gotoActions.addView(gotoStatusRefreshButton, weightWrap(1f));

        nightModeButton = actionButton(nightModeEnabled ? R.string.night_mode_off : R.string.night_mode_on);
        nightModeButton.setOnClickListener(v -> toggleNightMode());
        gotoActions.addView(nightModeButton, weightWrapWithLeftMargin(1f, 8));
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
        logText = bodyText(R.string.log_empty);
        logText.setTextColor(bodyTextColor());
        logText.setMinLines(5);
        logText.setGravity(Gravity.START);
        panel.addView(logText, matchWrap());
        updateLogText();
        return panel;
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
                    smallBodyDownloadAsteroidsButton.setEnabled(true);
                    updateSmallBodyStatusText();
                    if (skyChartView != null) {
                        skyChartView.invalidate();
                    }
                });
            } catch (Throwable t) {
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
        EditText input = new EditText(this);
        input.setHint(R.string.small_bodies_add_comet_hint);
        input.setSingleLine(true);
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
        setStatus(getString(R.string.small_bodies_download_starting));
        ioExecutor.execute(() -> {
            try {
                String json = fetchSbdbResolvingMultiMatch(query, isComet);
                SmallBody body = parseSbdbSingleRecord(json, isComet);
                if (body == null) {
                    runOnUiThread(() -> {
                        setStatus(getString(R.string.small_bodies_add_not_found, query));
                        if (smallBodyDownloadCometsButton != null) {
                            smallBodyDownloadCometsButton.setEnabled(true);
                        }
                    });
                    return;
                }
                smallBodyCatalog.addUserBody(body);
                runOnUiThread(() -> {
                    setStatus(getString(R.string.small_bodies_add_done, body.displayLabel()));
                    if (smallBodyDownloadCometsButton != null) {
                        smallBodyDownloadCometsButton.setEnabled(true);
                    }
                    updateSmallBodyStatusText();
                    if (skyChartView != null) {
                        skyChartView.invalidate();
                    }
                });
            } catch (Throwable t) {
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
        try {
            smallBodyCatalog.clearUserBodies();
            updateSmallBodyStatusText();
            if (skyChartView != null) {
                skyChartView.invalidate();
            }
            setStatus(getString(R.string.small_bodies_cleared));
        } catch (IOException ex) {
            setStatus(getString(R.string.small_bodies_clear_failed, safeMessage(ex)));
        }
    }

    private void showLayerDialog() {
        if (skyChartView == null) {
            return;
        }
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
                        skyChartView.setLayerVisible(layers[which], isChecked))
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
                return httpGetAcceptingCodesInternal(urlStr, allow300, true);
            }
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
            boolean ok = code == 200 || (allow300 && code == 300);
            if (!ok) {
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

    private View createHomePanel() {
        LinearLayout panel = card();

        homeStatusText = bodyText(R.string.home_status_none);
        homeStatusText.setPadding(0, 0, 0, dp(10));
        panel.addView(homeStatusText, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        gotoHomeButton = actionButton(R.string.home_goto);
        gotoHomeButton.setOnClickListener(v -> gotoHome());
        actions.addView(gotoHomeButton, weightWrap(1f));

        setHomeButton = actionButton(R.string.home_set_current);
        setHomeButton.setOnClickListener(v -> setHomeFromMount());
        actions.addView(setHomeButton, weightWrapWithLeftMargin(1f, 8));

        panel.addView(actions, matchWrap());
        updateHomeViews();
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

        TextView hostLabel = labelText(R.string.host_label);
        connectionForm.addView(hostLabel, matchWrap());

        hostField = new EditText(this);
        hostField.setSingleLine(true);
        hostField.setText(R.string.default_onstep_host);
        hostField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        connectionForm.addView(hostField, matchWrap());

        TextView portLabel = labelText(R.string.port_label);
        portLabel.setPadding(0, dp(8), 0, 0);
        connectionForm.addView(portLabel, matchWrap());

        portField = new EditText(this);
        portField.setSingleLine(true);
        portField.setText(String.format(Locale.US, "%d", DEFAULT_PORT));
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        connectionForm.addView(portField, matchWrap());
        panel.addView(connectionForm, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        connectButton = new Button(this);
        connectButton.setAllCaps(false);
        compactButton(connectButton);
        connectButton.setText(R.string.connect_button);
        connectButton.setOnClickListener(v -> connect());
        actions.addView(connectButton, weightWrap(1f));

        disconnectButton = new Button(this);
        disconnectButton.setAllCaps(false);
        compactButton(disconnectButton);
        disconnectButton.setText(R.string.disconnect_button);
        disconnectButton.setOnClickListener(v -> disconnect());
        actions.addView(disconnectButton, weightWrapWithLeftMargin(1f, 8));

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

        ImageView badge = new ImageView(this);
        badge.setImageResource(R.drawable.clearsky_badge);
        badge.setAdjustViewBounds(true);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));

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
                selectedManualRate = rate;
                if (connected) {
                    enqueueCommand(selectedManualRate.command, getString(R.string.log_rate_changed));
                }
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
            activeDirection = null;
            enqueueStop(getString(R.string.log_stop_sent));
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
            setStatus(getString(R.string.status_bad_port));
            return;
        }

        if (host.isEmpty()) {
            setStatus(getString(R.string.status_bad_host));
            return;
        }

        busy = true;
        setStatus(getString(R.string.status_connecting));
        updateUiState();
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
                    hasThreeStarTrackingModel = false;
                    trackingEnabled = false;
                    trackingUsingDualAxis = false;
                    gotoInProgress = false;
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
                    parked = false;
                    setStatus(getString(R.string.status_connect_failed, ex.getMessage()));
                    setSafetyStatus(getString(R.string.safety_status_connect_failed));
                    appendLog("ERROR " + safeMessage(ex));
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
        busy = true;
        connectionGeneration.incrementAndGet();
        setStatus(getString(R.string.status_disconnecting));
        updateUiState();
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
                hasThreeStarTrackingModel = false;
                activeDirection = null;
                alignmentSession = null;
                gotoInProgress = false;
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
        clearSyncedCurrentTarget();
        activeDirection = null;
        gotoInProgress = false;
        setGotoStatus(getString(R.string.goto_status_cancelled));
        setSafetyStatus(getString(R.string.safety_status_emergency_stop));
        enqueueImmediateStop(getString(R.string.status_emergency_stop_sent));
        updateUiState();
    }

    private void cancelGoto() {
        if (!connected) {
            setGotoStatus(getString(R.string.goto_status_not_connected));
            return;
        }
        clearSyncedCurrentTarget();
        activeDirection = null;
        gotoInProgress = false;
        setGotoStatus(getString(R.string.goto_status_cancelled));
        setSafetyStatus(getString(R.string.safety_status_goto_cancelled));
        enqueueImmediateStop(getString(R.string.goto_cancel_sent));
        updateUiState();
    }

    private void refreshGotoStatus() {
        if (!connected || busy) {
            return;
        }
        busy = true;
        setGotoStatus(getString(R.string.goto_status_refreshing));
        updateUiState();
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
        updateUiState();
        updateCalibrationViews();
    }

    private void handleMotionCommandFailure(IOException ex) {
        busy = false;
        activeDirection = null;
        setStatus(getString(R.string.status_motion_failed_keep_connected, safeMessage(ex)));
        appendLog("WARN motion " + safeMessage(ex));
        updateUiState();
    }

    private void showTargetDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
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
                        setStatus(getString(R.string.target_not_found));
                        return;
                    }
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
            setStatus(getString(R.string.status_goto_already_synced, target.label));
            setGotoStatus(getString(R.string.goto_status_already_synced, target.label));
            appendLog("INFO GOTO skipped; " + target.label + " is the synced current target");
            if (onSuccess != null) {
                onSuccess.run();
            }
            updateUiState();
            return;
        }
        clearSyncedCurrentTarget();
        EquatorialPoint commandPoint = gotoCommandPoint(target);
        String raCommand = ":Sr" + formatRightAscensionCommand(commandPoint.raHours) + "#";
        String decCommand = ":Sd" + formatDeclinationCommand(commandPoint.decDegrees) + "#";
        busy = true;
        setStatus(sendingStatus);
        updateUiState();
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
                    parked = false;
                    setStatus(sentStatus);
                    setGotoStatus(getString(R.string.goto_status_sent, target.label));
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    updateUiState();
                });
            } catch (CommandRejectedException ex) {
                runOnUiThread(() -> {
                    busy = false;
                    gotoInProgress = false;
                    setStatus(commandRejectedStatus(ex.command, ex.reply));
                    setGotoStatus(getString(R.string.goto_status_rejected, ex.reply));
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
    }

    private void clearQuickPointingCorrection() {
        quickPointingCorrectionActive = false;
        quickPointingRaOffsetHours = 0.0;
        quickPointingDecOffsetDegrees = 0.0;
    }

    private void captureInitialHomeFromMount() {
        captureHomeFromMount(false);
    }

    private void setHomeFromMount() {
        captureHomeFromMount(true);
    }

    private void captureHomeFromMount(boolean userRequested) {
        if (!connected || busy) {
            return;
        }
        busy = true;
        String sendingStatus = getString(userRequested ? R.string.home_set_sending : R.string.home_initial_sending);
        setStatus(sendingStatus);
        updateUiState();
        appendLog("TX :GR#");
        appendLog("TX :GD#");

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
                    appendLog("RX :GR# -> " + raReply);
                    appendLog("RX :GD# -> " + decReply);
                    busy = false;
                    setMountPointing(raHours, decDegrees);
                    setHomePosition(raHours, decDegrees);
                    setStatus(getString(
                            userRequested ? R.string.home_set_sent : R.string.home_initial_sent,
                            formatHourAngleDisplay(homeHourAngleHours),
                            formatDeclinationDisplay(decDegrees)
                    ));
                    updateUiState();
                });
            } catch (IOException | IllegalArgumentException ex) {
                runOnUiThread(() -> {
                    busy = false;
                    setStatus(getString(
                            userRequested ? R.string.home_set_failed : R.string.home_initial_failed,
                            safeMessage(ex)
                    ));
                    appendLog("WARN home " + safeMessage(ex));
                    updateUiState();
                });
            }
        });
    }

    private void gotoHome() {
        if (!connected || busy) {
            return;
        }
        if (!hasHomePosition) {
            setStatus(getString(R.string.home_no_position));
            return;
        }
        if (!homePositionValid) {
            setStatus(getString(R.string.home_invalid_no_goto));
            return;
        }
        SkyChartView.Target homeTarget = SkyChartView.Target.custom(
                getString(R.string.home_target_label),
                currentHomeRaHours(),
                homeDecDegrees
        );
        sendGotoTarget(
                homeTarget,
                getString(R.string.home_goto_sending),
                getString(R.string.home_goto_sent),
                null
        );
    }

    private void parkMount() {
        if (!connected || busy) {
            return;
        }
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(OnStepCommand.PARK.command));
        runMountCommands(
                commands,
                getString(R.string.park_sending),
                getString(R.string.park_sent),
                () -> {
                    parked = true;
                    gotoInProgress = false;
                    trackingEnabled = false;
                    trackingUsingDualAxis = false;
                    setGotoStatus(getString(R.string.goto_status_idle));
                    setSafetyStatus(getString(R.string.safety_status_parked));
                    updateTrackingViews();
                }
        );
    }

    private void unparkMount() {
        if (!connected || busy) {
            return;
        }
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(OnStepCommand.UNPARK.command));
        runMountCommands(
                commands,
                getString(R.string.unpark_sending),
                getString(R.string.unpark_sent),
                () -> {
                    parked = false;
                    setSafetyStatus(getString(R.string.safety_status_unparked));
                }
        );
    }

    private void syncObserverToMount() {
        if (!connected || busy) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(observerState.zoneId);
        List<String> commands = buildObserverSyncCommands(now);

        busy = true;
        setStatus(getString(R.string.status_sync_mount_sending));
        updateUiState();
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
        selectedTrackingRate = rate;
        updateTrackingViews();
        if (!connected || busy) {
            setStatus(getString(
                    R.string.tracking_rate_selected,
                    getString(rate.labelRes),
                    trackingModeLabel(shouldStartDualAxisTracking())
            ));
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
                }
        );
    }

    private void addTrackingStartCommands(List<MountCommand> commands, boolean dualAxis) {
        commands.add(MountCommand.noReply(selectedTrackingRate.command));
        if (dualAxis) {
            commands.add(MountCommand.noReply(OnStepCommand.TRACK_FULL_COMPENSATION.command));
            commands.add(MountCommand.noReply(OnStepCommand.TRACK_DUAL_AXIS.command));
        }
    }

    private boolean shouldStartDualAxisTracking() {
        return hasThreeStarTrackingModel;
    }

    private String trackingModeLabel(boolean dualAxis) {
        return getString(dualAxis ? R.string.tracking_mode_dual_axis : R.string.tracking_mode_single_axis);
    }

    private void fillSuggestedCalibrationTarget() {
        if (skyChartView == null) {
            return;
        }
        List<SkyChartView.Target> suggestions = skyChartView.suggestedAlignmentTargets(12);
        if (suggestions.isEmpty()) {
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
                    R.string.calibration_align_selected_manual,
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
        setCalibrationTarget(target);
        applySkyTarget(target, true);
    }

    private void selectQuickCalibrationTarget() {
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
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

        String raCommand = ":Sr" + formatRightAscensionCommand(target.raHours) + "#";
        String decCommand = ":Sd" + formatDeclinationCommand(target.decDegrees) + "#";
        String sendingStatus = getString(R.string.calibration_quick_sync_sending, target.label);
        busy = true;
        setStatus(sendingStatus);
        setCalibrationStatus(sendingStatus);
        updateUiState();
        appendLog("TX " + OnStepCommand.STOP_ALL.command);
        appendLog("TX :GR#");
        appendLog("TX :GD#");
        appendLog("TX " + raCommand);
        appendLog("TX " + decCommand);
        appendLog("TX " + OnStepCommand.SYNC_CURRENT_TARGET.command);
        appendLog("TX " + OnStepCommand.TRACK_SIDEREAL.command);
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
                    trackingUsingDualAxis = false;
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
                    updateUiState();
                });
            }
        });
    }

    private void startAlignment(int starCount) {
        if (!connected || busy || alignmentSession != null) {
            return;
        }
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
        appendLog("INFO alignment start syncs observer time/location first");
        runMountCommands(
                commands,
                getString(R.string.calibration_align_starting, starCount),
                getString(R.string.calibration_align_started, starCount),
                () -> {
                    alignmentSession = new AlignmentSession(starCount);
                    hasThreeStarTrackingModel = false;
                    selectedTrackingRate = TrackingRate.SIDEREAL;
                    trackingEnabled = true;
                    trackingUsingDualAxis = false;
                    calibrationTarget = null;
                    calibrationTargetField.setText("");
                    clearSyncedCurrentTarget();
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    setCalibrationStatus(getString(R.string.calibration_align_started, starCount));
                    updateTrackingViews();
                    updateCalibrationViews();
                }
        );
    }

    private void selectAlignmentTargetOnly() {
        SkyChartView.Target target = selectAlignmentTarget(true);
        if (target == null) {
            return;
        }
        setCalibrationStatus(getString(
                R.string.calibration_align_selected_manual,
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
        sendGotoTarget(
                target,
                getString(
                        R.string.calibration_align_goto_sending,
                        alignmentSession.currentStarNumber(),
                        alignmentSession.totalStars,
                        target.label
                ),
                getString(R.string.calibration_align_goto_sent, target.label),
                () -> setCalibrationStatus(getString(R.string.calibration_align_goto_center_prompt, target.label))
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
                if (acceptedBefore == 0 && isPierSideMismatch(preSnapshot.pierSide, expectedPierSide)) {
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
                } else {
                    setStatus(finalFailureStatus);
                    setCalibrationStatus(getString(R.string.calibration_align_diag_hint, finalFailureStatus));
                    appendLog("DIAG ALIGN_ACCEPT app remains at accepted="
                            + (alignmentSession == null ? "null" : alignmentSession.acceptedStars));
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
            if (alignmentSession.totalStars >= 3) {
                hasThreeStarTrackingModel = true;
                trackingEnabled = true;
                trackingUsingDualAxis = false;
            }
            setCalibrationStatus(getString(R.string.calibration_align_complete, alignmentSession.totalStars));
            alignmentSession.currentTarget = null;
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
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(OnStepCommand.ALIGN_WRITE.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_align_saving),
                getString(R.string.calibration_align_saved),
                () -> {
                    if (alignmentSession.totalStars >= 3) {
                        hasThreeStarTrackingModel = true;
                        trackingEnabled = true;
                        trackingUsingDualAxis = false;
                    }
                    alignmentSession = null;
                    calibrationTarget = null;
                    clearSyncedCurrentTarget();
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    setCalibrationStatus(getString(R.string.calibration_align_saved));
                    updateTrackingViews();
                    updateCalibrationViews();
                }
        );
    }

    private void cancelAlignmentSession() {
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
    }

    private void gotoRefinePolarTarget() {
        if (!hasThreeStarTrackingModel) {
            setCalibrationStatus(getString(R.string.calibration_refine_requires_three_star));
            return;
        }
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
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
                }
        );
    }

    private void refinePolarAlignment() {
        if (!hasThreeStarTrackingModel) {
            setCalibrationStatus(getString(R.string.calibration_refine_requires_three_star));
            return;
        }
        if (polarRefineSyncedTarget == null) {
            setCalibrationStatus(getString(R.string.calibration_refine_requires_sync));
            return;
        }
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.noReply(OnStepCommand.REFINE_POLAR_ALIGNMENT.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_refine_sending),
                getString(R.string.calibration_refine_sent),
                () -> {
                    hasThreeStarTrackingModel = true;
                    clearSyncedCurrentTarget();
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    setCalibrationStatus(getString(R.string.calibration_refine_sent));
                    updateTrackingViews();
                    updateCalibrationViews();
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
        if (calibrationStatusText != null) {
            calibrationStatusText.setText(status);
        }
        setStatus(status);
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

    private void updateCalibrationModeViews() {
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
        if (calibrationStatusText != null) {
            calibrationStatusText.setText(sendingStatus);
        }
        updateUiState();
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
                    if (calibrationStatusText != null) {
                        calibrationStatusText.setText(successStatus);
                    }
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
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

    private void setHomePosition(double raHours, double decDegrees) {
        Instant now = Instant.now();
        hasHomePosition = true;
        homePositionValid = true;
        homeReferenceRaHours = normalizeHours(raHours);
        homeHourAngleHours = signedHourAngleHours(raHours, now);
        homeDecDegrees = clamp(decDegrees, -90.0, 90.0);
        homeReferenceInstant = now;
        saveHomePosition();
        updateHomeViews();
    }

    private double currentHomeRaHours() {
        if (homeReferenceInstant == null) {
            return homeRaHoursFromHourAngle(Instant.now());
        }
        return normalizeHours(homeReferenceRaHours + siderealDeltaHours(homeReferenceInstant, Instant.now()));
    }

    private double homeRaHoursFromHourAngle(Instant instant) {
        return normalizeHours(localSiderealDegrees(instant) / 15.0 - homeHourAngleHours);
    }

    private void loadHomePosition() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!preferences.getBoolean(PREF_HOME_HAS_POSITION, false)) {
            hasHomePosition = false;
            homePositionValid = false;
            return;
        }
        homeHourAngleHours = clampHourAngle(readDoublePreference(preferences, PREF_HOME_HOUR_ANGLE, 0.0));
        homeDecDegrees = clamp(readDoublePreference(preferences, PREF_HOME_DEC_DEGREES, 0.0), -90.0, 90.0);
        hasHomePosition = true;
        homePositionValid = preferences.getBoolean(PREF_HOME_VALID, false);
        if (preferences.contains(PREF_HOME_REFERENCE_RA) && preferences.contains(PREF_HOME_REFERENCE_EPOCH_MS)) {
            homeReferenceRaHours = normalizeHours(readDoublePreference(preferences, PREF_HOME_REFERENCE_RA, 0.0));
            homeReferenceInstant = Instant.ofEpochMilli(preferences.getLong(PREF_HOME_REFERENCE_EPOCH_MS, Instant.now().toEpochMilli()));
        } else {
            updateHomeReferenceSnapshot();
            saveHomePosition();
        }
    }

    private void saveHomePosition() {
        if (hasHomePosition && homeReferenceInstant == null) {
            updateHomeReferenceSnapshot();
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HOME_HAS_POSITION, hasHomePosition)
                .putBoolean(PREF_HOME_VALID, homePositionValid)
                .putLong(PREF_HOME_REFERENCE_RA, Double.doubleToRawLongBits(homeReferenceRaHours))
                .putLong(PREF_HOME_REFERENCE_EPOCH_MS, homeReferenceInstant == null ? 0L : homeReferenceInstant.toEpochMilli())
                .putLong(PREF_HOME_HOUR_ANGLE, Double.doubleToRawLongBits(homeHourAngleHours))
                .putLong(PREF_HOME_DEC_DEGREES, Double.doubleToRawLongBits(homeDecDegrees))
                .apply();
    }

    private void invalidateHomePosition() {
        if (!hasHomePosition || !homePositionValid) {
            updateHomeViews();
            return;
        }
        homePositionValid = false;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HOME_HAS_POSITION, true)
                .putBoolean(PREF_HOME_VALID, false)
                .apply();
        appendLog("HOME invalidated by coordinate model change");
        updateHomeViews();
    }

    private void updateHomeReferenceSnapshot() {
        if (!hasHomePosition) {
            homeReferenceInstant = null;
            return;
        }
        Instant now = Instant.now();
        homeReferenceRaHours = currentHomeRaHours();
        homeReferenceInstant = now;
        updateHomeViews();
    }

    private static double readDoublePreference(SharedPreferences preferences, String key, double fallback) {
        if (!preferences.contains(key)) {
            return fallback;
        }
        return Double.longBitsToDouble(preferences.getLong(key, Double.doubleToRawLongBits(fallback)));
    }

    private static double clampHourAngle(double hourAngleHours) {
        double normalized = normalizeHours(hourAngleHours);
        return normalized > 12.0 ? normalized - 24.0 : normalized;
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
        if (!gotoInProgress || selectedSkyTarget == null || !hasCurrentMountPosition) {
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

    private static double siderealDeltaHours(Instant start, Instant end) {
        return normalizeDegrees(greenwichSiderealDegrees(end) - greenwichSiderealDegrees(start)) / 15.0;
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
        EditText field = new EditText(this);
        field.setSingleLine(true);
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
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(latitudeField.getText().toString().trim());
            longitude = Double.parseDouble(longitudeField.getText().toString().trim());
        } catch (NumberFormatException ex) {
            setObserverMessage(getString(R.string.location_bad_input));
            return;
        }
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            setObserverMessage(getString(R.string.location_bad_input));
            return;
        }
        observerState = new ObserverState(latitude, longitude, ZoneId.systemDefault(), getString(R.string.manual_location_name));
        updateObserverViews();
    }

    private void requestGpsLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
            setObserverMessage(getString(R.string.gps_unavailable));
            return;
        }

        try {
            locationManager.requestSingleUpdate(provider, new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    applyDeviceLocation(location);
                }

                @Override
                public void onProviderDisabled(String provider) {
                    setObserverMessage(getString(R.string.gps_unavailable));
                }
            }, Looper.getMainLooper());
        } catch (SecurityException ex) {
            setObserverMessage(getString(R.string.gps_permission_denied));
        } catch (IllegalArgumentException ex) {
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
    }

    private void toggleNightMode() {
        nightModeEnabled = !nightModeEnabled;
        applyNightModeWindow();
        setContentView(createContentView());
        updateUiState();
        updateObserverViews();
        updateTargetViews();
        updateGotoStatusViews();
        updateSafetyStatusViews();
        updateLogText();
    }

    private void applyNightModeWindow() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = nightModeEnabled
                ? 0.08f
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(attributes);
    }

    private int pageBackgroundColor() {
        return nightModeEnabled ? Color.rgb(18, 4, 4) : Color.rgb(245, 247, 251);
    }

    private int cardBackgroundColor() {
        return nightModeEnabled ? Color.rgb(38, 7, 7) : Color.WHITE;
    }

    private int titleTextColor() {
        return nightModeEnabled ? Color.rgb(255, 190, 190) : Color.rgb(17, 24, 39);
    }

    private int labelTextColor() {
        return nightModeEnabled ? Color.rgb(255, 175, 175) : Color.rgb(31, 41, 55);
    }

    private int bodyTextColor() {
        return nightModeEnabled ? Color.rgb(255, 145, 145) : Color.rgb(75, 85, 99);
    }

    private int mutedTextColor() {
        return nightModeEnabled ? Color.rgb(175, 80, 80) : Color.rgb(156, 163, 175);
    }

    private int selectedAccentColor() {
        return nightModeEnabled ? Color.rgb(255, 98, 98) : Color.rgb(14, 116, 144);
    }

    private LinearLayout card() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackgroundColor(cardBackgroundColor());
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

    private Button actionButton(int textRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(textRes);
        compactButton(button);
        return button;
    }

    private void compactButton(Button button) {
        button.setTextSize(14);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
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
                dp(sideMenuExpanded ? 148 : 56),
                sideMenuExpanded ? FrameLayout.LayoutParams.WRAP_CONTENT : dp(56)
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = dp(8);
        params.topMargin = dp(22);
        return params;
    }

    private FrameLayout.LayoutParams floatingStopParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(56), dp(48));
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = dp(8);
        params.topMargin = dp(sideMenuExpanded ? 286 : 86);
        return params;
    }

    private LinearLayout.LayoutParams sideMenuToggleParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
    }

    private LinearLayout.LayoutParams sideMenuButtonParams(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        params.topMargin = dp(topDp);
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
            drawable.setColor(enabled ? Color.WHITE : Color.rgb(249, 250, 251));
            drawable.setStroke(dp(1), enabled ? Color.rgb(75, 85, 99) : Color.rgb(229, 231, 235));
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
        drawable.setColor(nightModeEnabled ? Color.rgb(48, 10, 10) : Color.rgb(238, 244, 248));
        drawable.setStroke(dp(1), selectedAccentColor());
        return drawable;
    }

    private GradientDrawable createRateButtonBackground(boolean selected, boolean enabled) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        if (!enabled) {
            drawable.setColor(nightModeEnabled ? Color.rgb(32, 7, 7) : Color.rgb(249, 250, 251));
            drawable.setStroke(dp(1), nightModeEnabled ? Color.rgb(85, 30, 30) : Color.rgb(229, 231, 235));
        } else if (selected) {
            drawable.setColor(nightModeEnabled ? Color.rgb(74, 13, 13) : Color.rgb(224, 242, 254));
            drawable.setStroke(dp(2), selectedAccentColor());
        } else {
            drawable.setColor(cardBackgroundColor());
            drawable.setStroke(dp(1), nightModeEnabled ? Color.rgb(120, 45, 45) : Color.rgb(156, 163, 175));
        }
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private GradientDrawable createTabBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(selected ? Color.WHITE : Color.rgb(15, 23, 42));
        drawable.setStroke(dp(1), selected ? Color.WHITE : Color.rgb(51, 65, 85));
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
        logLines.addLast(line);
        while (logLines.size() > 12) {
            logLines.removeFirst();
        }
        updateLogText();
    }

    private void updateLogText() {
        StringBuilder builder = new StringBuilder();
        for (String logLine : logLines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(logLine);
        }
        if (logText != null) {
            logText.setText(builder.length() == 0 ? getString(R.string.log_empty) : builder.toString());
        }
    }

    private void updateUiState() {
        hostField.setEnabled(!connected && !busy);
        portField.setEnabled(!connected && !busy);
        connectButton.setEnabled(!connected && !busy);
        disconnectButton.setEnabled(connected && !busy);
        connectionForm.setVisibility(connected ? View.GONE : View.VISIBLE);
        connectButton.setVisibility(connected ? View.GONE : View.VISIBLE);
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
        }
        if (syncMountButton != null) {
            syncMountButton.setEnabled(connected && !busy);
        }
        if (trackingToggleButton != null) {
            trackingToggleButton.setEnabled(connected && !busy);
        }
        if (gotoHomeButton != null) {
            gotoHomeButton.setEnabled(connected && !busy && hasHomePosition && homePositionValid);
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
            refineGotoButton.setEnabled(connected && !busy && hasThreeStarTrackingModel);
        }
        if (refinePaButton != null) {
            refinePaButton.setEnabled(connected && !busy && hasThreeStarTrackingModel && polarRefineSyncedTarget != null);
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
        stopButton.setEnabled(controlsEnabled);
        stopButton.setBackground(createStopButtonBackground(controlsEnabled));
        stopButton.setTextColor(Color.WHITE);
        updateTrackingViews();
        updateHomeViews();
    }

    private void updateHomeViews() {
        if (homeStatusText != null) {
            if (!hasHomePosition) {
                homeStatusText.setText(R.string.home_status_none);
            } else if (!homePositionValid) {
                homeStatusText.setText(getString(
                        R.string.home_status_invalid,
                        formatHourAngleDisplay(homeHourAngleHours),
                        formatDeclinationDisplay(homeDecDegrees)
                ));
            } else {
                homeStatusText.setText(getString(
                        R.string.home_status,
                        formatHourAngleDisplay(homeHourAngleHours),
                        formatDeclinationDisplay(homeDecDegrees)
                ));
            }
        }
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
        styleTabButton(manualTabButton, selectedPage == Page.MANUAL);
        styleTabButton(skyTabButton, selectedPage == Page.SKY);
        styleTabButton(settingsTabButton, selectedPage == Page.SETTINGS);
    }

    private void selectPageFromMenu(Page selectedPage) {
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
        if (manualTabButton != null) {
            manualTabButton.setVisibility(menuItemVisibility);
        }
        if (skyTabButton != null) {
            skyTabButton.setVisibility(menuItemVisibility);
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
        button.setTextColor(selected ? Color.rgb(15, 23, 42) : Color.rgb(226, 232, 240));
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

    private enum Page {
        MANUAL,
        SKY,
        SETTINGS
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
