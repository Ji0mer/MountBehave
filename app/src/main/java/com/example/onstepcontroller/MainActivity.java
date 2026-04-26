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
import android.text.InputType;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import java.io.IOException;
import java.net.SocketTimeoutException;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;

public final class MainActivity extends Activity {
    private static final int DEFAULT_PORT = 9999;
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

    private boolean connected;
    private boolean busy;
    private boolean sideMenuExpanded;
    private boolean nightModeEnabled;
    private boolean gotoInProgress;
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
    private Direction activeDirection;
    private SkyChartView.Target selectedSkyTarget;
    private SkyChartView.Target calibrationTarget;
    private SkyChartView.Target syncedCurrentTarget;
    private SkyChartView.Target polarRefineSyncedTarget;
    private boolean quickPointingCorrectionActive;
    private double quickPointingRaOffsetHours;
    private double quickPointingDecOffsetDegrees;
    private ManualRate selectedManualRate = ManualRate.CENTER;
    private CalibrationMode selectedCalibrationMode = CalibrationMode.QUICK_SYNC;
    private Page currentPage = Page.SETTINGS;
    private AlignmentSession alignmentSession;
    private int suggestedCalibrationIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectivityManager = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyNightModeWindow();
        setContentView(createContentView());
        updateUiState();
        updateObserverViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        acquireWifiLock();
        uiHandler.post(skyClockRunnable);
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
        root.setPadding(dp(wideLayout ? 24 : 12), dp(18), dp(wideLayout ? 24 : 12), dp(24));
        scrollView.addView(root, matchWrap());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(64), 0, 0, dp(12));
        TextView title = titleText(R.string.app_name, 24);
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
        manualStatusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (currentStatusMessage != null) {
            manualStatusText.setText(currentStatusMessage);
        }
        manualPage.addView(manualStatusText, matchWrap());
        manualPage.addView(createCalibrationPage(), matchWrapWithTopMargin(12));
        manualPage.addView(sectionTitle(R.string.manual_control_section), matchWrapWithTopMargin(12));
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

        page.addView(sectionTitle(R.string.sky_section), matchWrap());

        LinearLayout panel = card();
        skySummaryText = bodyText(R.string.sky_loading);
        skySummaryText.setPadding(0, 0, 0, dp(10));
        panel.addView(skySummaryText, matchWrap());

        skyChartView = new SkyChartView(this);
        skyChartView.setObserver(observerState, Instant.now());
        skyChartView.setTargetSelectionListener(target -> {
            selectedSkyTarget = target;
            updateTargetViews();
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
        mountPointingText.setPadding(0, dp(4), 0, 0);
        if (hasCurrentMountPosition) {
            mountPointingText.setText(getString(
                    R.string.mount_pointing_status,
                    formatRightAscensionDisplay(currentMountRaHours),
                    formatDeclinationDisplay(currentMountDecDegrees)
            ));
        }
        panel.addView(mountPointingText, matchWrap());

        gotoStatusText = bodyText(R.string.goto_status_idle);
        gotoStatusText.setPadding(0, dp(4), 0, 0);
        if (gotoStatusMessage != null) {
            gotoStatusText.setText(gotoStatusMessage);
        }
        panel.addView(gotoStatusText, matchWrap());

        observingAlertText = bodyText(R.string.observing_alert_no_target);
        observingAlertText.setPadding(0, dp(4), 0, 0);
        panel.addView(observingAlertText, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);

        Button resetButton = new Button(this);
        resetButton.setAllCaps(false);
        resetButton.setText(R.string.sky_reset);
        resetButton.setOnClickListener(v -> {
            skyChartView.resetView();
            skySummaryText.setText(skyChartView.summary());
        });
        actions.addView(resetButton, weightWrap(1f));

        gotoButton = new Button(this);
        gotoButton.setAllCaps(false);
        gotoButton.setText(R.string.sky_goto_target);
        gotoButton.setOnClickListener(v -> showTargetDialog());
        actions.addView(gotoButton, weightWrapWithLeftMargin(1f, 10));

        skyCancelGotoButton = new Button(this);
        skyCancelGotoButton.setAllCaps(false);
        skyCancelGotoButton.setText(R.string.goto_cancel);
        skyCancelGotoButton.setOnClickListener(v -> cancelGoto());
        actions.addView(skyCancelGotoButton, weightWrapWithLeftMargin(1f, 10));

        panel.addView(actions, matchWrap());

        panel.addView(skyChartView, matchFixedHeight(isWideLayout() ? 620 : 480));

        TextView note = bodyText(R.string.sky_planet_note);
        note.setPadding(0, dp(10), 0, 0);
        panel.addView(note, matchWrap());

        page.addView(panel, matchWrap());
        return page;
    }

    private LinearLayout createCalibrationPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        page.addView(sectionTitle(R.string.calibration_section), matchWrap());

        LinearLayout targetPanel = card();
        TextView intro = bodyText(R.string.calibration_intro);
        intro.setPadding(0, 0, 0, dp(10));
        targetPanel.addView(intro, matchWrap());

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
        targetPanel.addView(calibrationTargetField, matchWrap());

        LinearLayout targetActions = new LinearLayout(this);
        targetActions.setOrientation(LinearLayout.HORIZONTAL);
        targetActions.setGravity(Gravity.CENTER_VERTICAL);
        targetActions.setPadding(0, dp(10), 0, 0);

        calibrationSuggestButton = actionButton(R.string.calibration_suggest_star);
        calibrationSuggestButton.setOnClickListener(v -> fillSuggestedCalibrationTarget());
        targetActions.addView(calibrationSuggestButton, weightWrap(1f));

        calibrationShowButton = actionButton(R.string.calibration_show_in_sky);
        calibrationShowButton.setOnClickListener(v -> showCalibrationTargetInSky());
        targetActions.addView(calibrationShowButton, weightWrapWithLeftMargin(1f, 8));

        targetPanel.addView(targetActions, matchWrap());

        calibrationStatusText = bodyText(R.string.calibration_status_idle);
        calibrationStatusText.setPadding(0, dp(10), 0, 0);
        targetPanel.addView(calibrationStatusText, matchWrap());
        page.addView(targetPanel, matchWrap());

        page.addView(sectionTitle(R.string.calibration_mode_settings), matchWrapWithTopMargin(12));

        quickCalibrationPanel = card();
        TextView quickIntro = bodyText(R.string.calibration_quick_intro);
        quickIntro.setPadding(0, 0, 0, dp(10));
        quickCalibrationPanel.addView(quickIntro, matchWrap());

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
        TextView alignIntro = bodyText(R.string.calibration_align_intro);
        alignIntro.setPadding(0, 0, 0, dp(10));
        alignCalibrationPanel.addView(alignIntro, matchWrap());

        alignStartButton = actionButton(R.string.calibration_align_start);
        alignStartButton.setOnClickListener(v -> {
            if (selectedCalibrationMode.starCount > 0) {
                startAlignment(selectedCalibrationMode.starCount);
            }
        });
        alignCalibrationPanel.addView(alignStartButton, matchWrap());

        calibrationStepText = bodyText(R.string.calibration_align_idle);
        calibrationStepText.setPadding(0, dp(10), 0, dp(10));
        alignCalibrationPanel.addView(calibrationStepText, matchWrap());

        alignmentCurrentText = bodyText(R.string.calibration_align_current_none);
        alignmentCurrentText.setPadding(0, 0, 0, dp(8));
        alignCalibrationPanel.addView(alignmentCurrentText, matchWrap());

        alignmentAcceptedText = bodyText(R.string.calibration_align_accepted_none);
        alignmentAcceptedText.setPadding(0, 0, 0, dp(10));
        alignCalibrationPanel.addView(alignmentAcceptedText, matchWrap());

        LinearLayout alignActionsOne = new LinearLayout(this);
        alignActionsOne.setOrientation(LinearLayout.VERTICAL);
        alignActionsOne.setGravity(Gravity.CENTER_VERTICAL);

        alignSelectButton = actionButton(R.string.calibration_align_select_current);
        alignSelectButton.setOnClickListener(v -> selectAlignmentTargetOnly());
        alignActionsOne.addView(alignSelectButton, matchWrap());

        alignCalibrationPanel.addView(alignActionsOne, matchWrap());

        LinearLayout alignActionsTwo = new LinearLayout(this);
        alignActionsTwo.setOrientation(LinearLayout.VERTICAL);
        alignActionsTwo.setGravity(Gravity.CENTER_VERTICAL);
        alignActionsTwo.setPadding(0, dp(8), 0, 0);

        alignAcceptButton = actionButton(R.string.calibration_align_accept);
        alignAcceptButton.setOnClickListener(v -> acceptAlignmentStar());
        alignActionsTwo.addView(alignAcceptButton, matchWrap());

        LinearLayout alignFinishActions = new LinearLayout(this);
        alignFinishActions.setOrientation(LinearLayout.HORIZONTAL);
        alignFinishActions.setGravity(Gravity.CENTER_VERTICAL);
        alignFinishActions.setPadding(0, dp(8), 0, 0);

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
        TextView refineIntro = bodyText(R.string.calibration_refine_intro);
        refineIntro.setPadding(0, 0, 0, dp(10));
        refineCalibrationPanel.addView(refineIntro, matchWrap());

        refineGotoButton = actionButton(R.string.calibration_refine_goto);
        refineGotoButton.setOnClickListener(v -> gotoRefinePolarTarget());
        refineCalibrationPanel.addView(refineGotoButton, matchWrap());

        refinePaButton = actionButton(R.string.calibration_refine_pa);
        refinePaButton.setOnClickListener(v -> refinePolarAlignment());
        refineCalibrationPanel.addView(refinePaButton, matchWrapWithTopMargin(8));

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
            left.addView(sectionTitle(R.string.observer_section), matchWrapWithTopMargin(12));
            left.addView(createObserverPanel(), matchWrap());
            columns.addView(left, weightWrap(1f));

            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.addView(sectionTitle(R.string.tracking_section), matchWrap());
            right.addView(createTrackingPanel(), matchWrap());
            right.addView(sectionTitle(R.string.command_log_section), matchWrapWithTopMargin(12));
            right.addView(createCommandLogPanel(), matchWrap());
            right.addView(sectionTitle(R.string.safety_section), matchWrapWithTopMargin(12));
            right.addView(createSafetyPanel(), matchWrap());
            columns.addView(right, weightWrapWithLeftMargin(1f, 16));

            page.addView(columns, matchWrap());
        } else {
            page.addView(sectionTitle(R.string.connection_section), matchWrap());
            page.addView(createConnectionPanel(), matchWrap());

            page.addView(sectionTitle(R.string.observer_section), matchWrapWithTopMargin(12));
            page.addView(createObserverPanel(), matchWrap());

            page.addView(sectionTitle(R.string.tracking_section), matchWrapWithTopMargin(12));
            page.addView(createTrackingPanel(), matchWrap());

            page.addView(sectionTitle(R.string.command_log_section), matchWrapWithTopMargin(12));
            page.addView(createCommandLogPanel(), matchWrap());

            page.addView(sectionTitle(R.string.safety_section), matchWrapWithTopMargin(12));
            page.addView(createSafetyPanel(), matchWrap());
        }

        return page;
    }

    private View createSafetyPanel() {
        LinearLayout panel = card();

        TextView intro = bodyText(R.string.safety_intro);
        intro.setPadding(0, 0, 0, dp(10));
        panel.addView(intro, matchWrap());

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
        gotoActions.setPadding(0, dp(8), 0, 0);

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
        parkActions.setPadding(0, dp(8), 0, 0);

        parkButton = actionButton(R.string.park_mount);
        parkButton.setOnClickListener(v -> parkMount());
        parkActions.addView(parkButton, weightWrap(1f));

        unparkButton = actionButton(R.string.unpark_mount);
        unparkButton.setOnClickListener(v -> unparkMount());
        parkActions.addView(unparkButton, weightWrapWithLeftMargin(1f, 8));
        panel.addView(parkActions, matchWrap());

        safetyStatusText = bodyText(R.string.safety_status_idle);
        safetyStatusText.setPadding(0, dp(10), 0, 0);
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

    private View createTrackingPanel() {
        LinearLayout panel = card();

        TextView intro = bodyText(R.string.tracking_intro);
        intro.setPadding(0, 0, 0, dp(10));
        panel.addView(intro, matchWrap());

        panel.addView(labelText(R.string.tracking_rate_label), matchWrap());

        LinearLayout rateActions = new LinearLayout(this);
        rateActions.setOrientation(LinearLayout.HORIZONTAL);
        rateActions.setGravity(Gravity.CENTER_VERTICAL);
        rateActions.setPadding(0, dp(8), 0, dp(10));

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
        trackingStatusText.setPadding(0, dp(10), 0, 0);
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
        fields.addView(longitudeBox, weightWrapWithLeftMargin(1f, 10));

        panel.addView(fields, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);

        Button gpsButton = new Button(this);
        gpsButton.setAllCaps(false);
        gpsButton.setText(R.string.use_gps);
        gpsButton.setOnClickListener(v -> requestGpsLocation());
        actions.addView(gpsButton, weightWrap(1f));

        Button applyButton = new Button(this);
        applyButton.setAllCaps(false);
        applyButton.setText(R.string.apply_location);
        applyButton.setOnClickListener(v -> applyManualLocation());
        actions.addView(applyButton, weightWrapWithLeftMargin(1f, 8));

        panel.addView(actions, matchWrap());

        syncMountButton = new Button(this);
        syncMountButton.setAllCaps(false);
        syncMountButton.setText(R.string.sync_observer_to_mount);
        syncMountButton.setOnClickListener(v -> syncObserverToMount());
        LinearLayout.LayoutParams syncParams = matchWrapWithTopMargin(10);
        panel.addView(syncMountButton, syncParams);

        observerStatusText = bodyText(R.string.sky_loading);
        observerStatusText.setPadding(0, dp(10), 0, 0);
        panel.addView(observerStatusText, matchWrap());

        timeStatusText = bodyText(R.string.sky_loading);
        timeStatusText.setPadding(0, dp(4), 0, 0);
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
        portLabel.setPadding(0, dp(12), 0, 0);
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
        actions.setPadding(0, dp(10), 0, 0);

        connectButton = new Button(this);
        connectButton.setAllCaps(false);
        connectButton.setText(R.string.connect_button);
        connectButton.setOnClickListener(v -> connect());
        actions.addView(connectButton, weightWrap(1f));

        disconnectButton = new Button(this);
        disconnectButton.setAllCaps(false);
        disconnectButton.setText(R.string.disconnect_button);
        disconnectButton.setOnClickListener(v -> disconnect());
        actions.addView(disconnectButton, weightWrapWithLeftMargin(1f, 10));

        panel.addView(actions, matchWrap());

        statusText = bodyText(R.string.status_disconnected);
        statusText.setPadding(0, dp(10), 0, 0);
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
        row.setPadding(0, 0, 0, dp(12));

        ImageView badge = new ImageView(this);
        badge.setImageResource(R.drawable.clearsky_badge);
        badge.setAdjustViewBounds(true);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(badge, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        TextView title = titleText(R.string.mount_profile_title, 16);
        textColumn.addView(title, matchWrap());

        TextView body = bodyText(R.string.mount_profile_body);
        body.setPadding(0, dp(3), 0, 0);
        textColumn.addView(body, matchWrap());

        row.addView(textColumn, weightWrapWithLeftMargin(1f, 12));
        return row;
    }

    private View createControlPanel() {
        LinearLayout panel = card();

        TextView rateLabel = labelText(R.string.rate_label);
        panel.addView(rateLabel, matchWrap());

        LinearLayout ratePanel = new LinearLayout(this);
        ratePanel.setOrientation(LinearLayout.VERTICAL);
        ratePanel.setPadding(0, dp(6), 0, dp(12));

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
        stopButton.setTextSize(18);
        stopButton.setTypeface(Typeface.DEFAULT_BOLD);
        stopButton.setMinHeight(dp(58));
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
        clearSyncedCurrentTarget();
        activeDirection = direction;
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
                    setStatus(getString(R.string.status_command_rejected, ex.command, ex.reply));
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
        List<String> commands = new ArrayList<>();
        commands.add(formatLatitudeCommand(observerState.latitudeDegrees));
        commands.add(formatLongitudeCommand(observerState.longitudeDegrees));
        commands.add(formatUtcOffsetCommand(now));
        commands.add(String.format(Locale.US, ":SL%02d:%02d:%02d#", now.getHour(), now.getMinute(), now.getSecond()));
        commands.add(String.format(Locale.US, ":SC%02d/%02d/%02d#", now.getMonthValue(), now.getDayOfMonth(), now.getYear() % 100));

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
                    setCalibrationStatus(getString(R.string.status_command_rejected, ex.command, ex.reply));
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
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(startCommand.command));
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
                    fillSuggestedCalibrationTarget();
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
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.noReply(OnStepCommand.STOP_ALL.command));
        commands.add(MountCommand.withReply(":Sr" + formatRightAscensionCommand(acceptedTarget.raHours) + "#"));
        commands.add(MountCommand.withReply(":Sd" + formatDeclinationCommand(acceptedTarget.decDegrees) + "#"));
        commands.add(MountCommand.withReply(OnStepCommand.SYNC_CURRENT_TARGET.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_align_accepting, acceptedTarget.label),
                getString(R.string.calibration_align_accepted, acceptedTarget.label),
                () -> {
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
                        fillSuggestedCalibrationTarget();
                    }
                    clearSyncedCurrentTarget();
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    updateCalibrationViews();
                    updateTrackingViews();
                    refreshMountPointing();
                }
        );
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
                    clearSyncedCurrentTarget();
                    clearQuickPointingCorrection();
                    polarRefineSyncedTarget = null;
                    setCalibrationStatus(getString(R.string.calibration_align_saved));
                    updateTrackingViews();
                }
        );
    }

    private void cancelAlignmentSession() {
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
        return target;
    }

    private void setCalibrationTarget(SkyChartView.Target target) {
        calibrationTarget = target;
        if (calibrationTargetField != null) {
            calibrationTargetField.setText(target.label);
            calibrationTargetField.setSelection(calibrationTargetField.getText().length());
        }
    }

    private void setCalibrationStatus(String status) {
        if (calibrationStatusText != null) {
            calibrationStatusText.setText(status);
        }
        setStatus(status);
    }

    private void updateCalibrationViews() {
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
                    setCalibrationStatus(getString(R.string.status_command_rejected, ex.command, ex.reply));
                    updateUiState();
                });
            } catch (IOException ex) {
                markTransportFault();
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
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
        if (!clean.contains(":")) {
            return normalizeHours(Double.parseDouble(clean));
        }
        String[] parts = clean.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid RA: " + value);
        }
        double hours = Double.parseDouble(parts[0]);
        double minutes = Double.parseDouble(parts[1]);
        double seconds = parts.length >= 3 ? Double.parseDouble(parts[2]) : 0.0;
        return normalizeHours(hours + minutes / 60.0 + seconds / 3600.0);
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
        observerState = new ObserverState(latitude, longitude, observerState.zoneId, getString(R.string.manual_location_name));
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
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
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
        TextView textView = titleText(textRes, 17);
        textView.setPadding(0, 0, 0, dp(6));
        return textView;
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
        textView.setTextSize(15);
        textView.setTextColor(bodyTextColor());
        textView.setGravity(Gravity.START);
        return textView;
    }

    private Button actionButton(int textRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(textRes);
        return button;
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

    private LinearLayout.LayoutParams controlButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(68), dp(68));
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
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
