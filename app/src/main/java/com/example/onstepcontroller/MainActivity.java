package com.example.onstepcontroller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
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

public final class MainActivity extends Activity {
    private static final int DEFAULT_PORT = 9999;
    private static final int LOCATION_PERMISSION_REQUEST = 24;
    private static final Pattern COORDINATE_TARGET_PATTERN = Pattern.compile(
            "^\\s*(?:RA\\s*)?([0-9]{1,2}(?::[0-9]{1,2}(?::[0-9]{1,2}(?:\\.\\d+)?)?)?|[0-9]+(?:\\.\\d+)?)\\s*[, ]+\\s*(?:DEC\\s*)?([+-]?[0-9]{1,2}(?:(?::|\\*|°)[0-9]{1,2}(?:(?::|')?[0-9]{1,2}(?:\\.\\d+)?)?)?|[+-]?[0-9]+(?:\\.\\d+)?)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final OnStepClient client = new OnStepClient();
    private final Deque<String> logLines = new ArrayDeque<>();
    private final Runnable skyClockRunnable = new Runnable() {
        @Override
        public void run() {
            updateSkyTime();
            if (connected && !busy) {
                refreshMountPointing();
            }
            uiHandler.postDelayed(this, 30_000);
        }
    };

    private Button manualTabButton;
    private Button skyTabButton;
    private Button settingsTabButton;
    private Button calibrationTabButton;
    private Button sideMenuToggleButton;
    private LinearLayout sideMenu;
    private LinearLayout manualPage;
    private LinearLayout skyPage;
    private LinearLayout settingsPage;
    private LinearLayout calibrationPage;
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
    private LinearLayout rateGrid;
    private final List<Button> rateButtons = new ArrayList<>();
    private String selectedRateCommand = OnStepCommand.RATE_CENTER.command;
    private ObserverState observerState = ObserverState.boston();
    private EditText latitudeField;
    private EditText longitudeField;
    private TextView observerStatusText;
    private TextView timeStatusText;
    private SkyChartView skyChartView;
    private TextView skySummaryText;
    private TextView targetStatusText;
    private TextView mountPointingText;
    private Button gotoButton;
    private Button syncMountButton;
    private EditText calibrationTargetField;
    private TextView calibrationStatusText;
    private TextView calibrationStepText;
    private Button calibrationSuggestButton;
    private Button calibrationShowButton;
    private Button quickGotoButton;
    private Button quickSyncButton;
    private Button alignOneButton;
    private Button alignTwoButton;
    private Button alignThreeButton;
    private Button alignGotoButton;
    private Button alignAcceptButton;
    private Button alignSaveButton;
    private Button alignCancelButton;
    private Button alignOpenManualButton;
    private Button modelTrackingButton;
    private Button refinePaButton;
    private TextView statusText;
    private TextView manualStatusText;
    private TextView logText;
    private LocationManager locationManager;

    private boolean connected;
    private boolean busy;
    private boolean sideMenuExpanded = true;
    private Direction activeDirection;
    private SkyChartView.Target selectedSkyTarget;
    private SkyChartView.Target calibrationTarget;
    private AlignmentSession alignmentSession;
    private int suggestedCalibrationIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        updateUiState();
        updateObserverViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setBackgroundColor(Color.rgb(245, 247, 251));

        shell.addView(createPageTabs(), sideMenuParams());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(245, 247, 251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(18), dp(12), dp(20));
        scrollView.addView(root, matchWrap());

        TextView title = titleText(R.string.app_name, 24);
        root.addView(title, matchWrap());

        TextView subtitle = bodyText(R.string.app_subtitle);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle, matchWrap());

        manualPage = new LinearLayout(this);
        manualPage.setOrientation(LinearLayout.VERTICAL);
        manualStatusText = bodyText(R.string.status_disconnected);
        manualStatusText.setTextColor(Color.rgb(31, 41, 55));
        manualStatusText.setBackgroundColor(Color.WHITE);
        manualStatusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        manualPage.addView(manualStatusText, matchWrap());
        manualPage.addView(sectionTitle(R.string.manual_control_section), matchWrapWithTopMargin(12));
        manualPage.addView(createControlPanel(), matchWrap());

        manualPage.addView(sectionTitle(R.string.command_log_section), matchWrapWithTopMargin(12));
        logText = bodyText(R.string.log_empty);
        logText.setTextColor(Color.rgb(55, 65, 81));
        logText.setMinLines(4);
        logText.setGravity(Gravity.START);
        logText.setBackgroundColor(Color.WHITE);
        logText.setPadding(dp(14), dp(12), dp(14), dp(12));
        manualPage.addView(logText, matchWrap());
        root.addView(manualPage, matchWrap());

        skyPage = createSkyPage();
        skyPage.setVisibility(View.GONE);
        root.addView(skyPage, matchWrap());

        calibrationPage = createCalibrationPage();
        calibrationPage.setVisibility(View.GONE);
        root.addView(calibrationPage, matchWrap());

        settingsPage = createSettingsPage();
        settingsPage.setVisibility(View.GONE);
        root.addView(settingsPage, matchWrap());

        updatePageTabs(Page.MANUAL);

        shell.addView(scrollView, matchParentWeight(1f));
        return shell;
    }

    private View createPageTabs() {
        sideMenu = new LinearLayout(this);
        sideMenu.setOrientation(LinearLayout.VERTICAL);
        sideMenu.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sideMenu.setPadding(dp(8), dp(22), dp(8), dp(8));
        sideMenu.setBackgroundColor(Color.rgb(15, 23, 42));

        sideMenuToggleButton = new Button(this);
        configureMenuToggleButton(sideMenuToggleButton);
        sideMenuToggleButton.setOnClickListener(v -> setSideMenuExpanded(!sideMenuExpanded));
        sideMenu.addView(sideMenuToggleButton, sideMenuToggleParams());

        settingsTabButton = new Button(this);
        configureTabButton(settingsTabButton);
        settingsTabButton.setText(R.string.tab_settings);
        settingsTabButton.setOnClickListener(v -> selectPageFromMenu(Page.SETTINGS));
        sideMenu.addView(settingsTabButton, sideMenuButtonParams(10));

        calibrationTabButton = new Button(this);
        configureTabButton(calibrationTabButton);
        calibrationTabButton.setText(R.string.tab_calibration);
        calibrationTabButton.setOnClickListener(v -> selectPageFromMenu(Page.CALIBRATION));
        sideMenu.addView(calibrationTabButton, sideMenuButtonParams(10));

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
        skySummaryText.setText(skyChartView.summary());

        targetStatusText = bodyText(R.string.sky_target_none);
        targetStatusText.setPadding(0, 0, 0, 0);
        panel.addView(targetStatusText, matchWrap());

        mountPointingText = bodyText(R.string.mount_pointing_default);
        mountPointingText.setPadding(0, dp(4), 0, 0);
        panel.addView(mountPointingText, matchWrap());

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

        panel.addView(actions, matchWrap());

        panel.addView(skyChartView, matchFixedHeight(480));

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

        page.addView(sectionTitle(R.string.calibration_quick_section), matchWrapWithTopMargin(12));
        LinearLayout quickPanel = card();
        TextView quickIntro = bodyText(R.string.calibration_quick_intro);
        quickIntro.setPadding(0, 0, 0, dp(10));
        quickPanel.addView(quickIntro, matchWrap());

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.CENTER_VERTICAL);

        quickGotoButton = actionButton(R.string.calibration_quick_goto);
        quickGotoButton.setOnClickListener(v -> quickGotoCalibrationTarget());
        quickActions.addView(quickGotoButton, weightWrap(1f));

        quickSyncButton = actionButton(R.string.calibration_quick_sync);
        quickSyncButton.setOnClickListener(v -> syncQuickCalibrationTarget());
        quickActions.addView(quickSyncButton, weightWrapWithLeftMargin(1f, 8));

        quickPanel.addView(quickActions, matchWrap());
        page.addView(quickPanel, matchWrap());

        page.addView(sectionTitle(R.string.calibration_align_section), matchWrapWithTopMargin(12));
        LinearLayout alignPanel = card();
        TextView alignIntro = bodyText(R.string.calibration_align_intro);
        alignIntro.setPadding(0, 0, 0, dp(10));
        alignPanel.addView(alignIntro, matchWrap());

        LinearLayout modeActions = new LinearLayout(this);
        modeActions.setOrientation(LinearLayout.HORIZONTAL);
        modeActions.setGravity(Gravity.CENTER_VERTICAL);

        alignOneButton = actionButton(R.string.calibration_one_star);
        alignOneButton.setOnClickListener(v -> startAlignment(1));
        modeActions.addView(alignOneButton, weightWrap(1f));

        alignTwoButton = actionButton(R.string.calibration_two_star);
        alignTwoButton.setOnClickListener(v -> startAlignment(2));
        modeActions.addView(alignTwoButton, weightWrapWithLeftMargin(1f, 8));

        alignThreeButton = actionButton(R.string.calibration_three_star);
        alignThreeButton.setOnClickListener(v -> startAlignment(3));
        modeActions.addView(alignThreeButton, weightWrapWithLeftMargin(1f, 8));

        alignPanel.addView(modeActions, matchWrap());

        calibrationStepText = bodyText(R.string.calibration_align_idle);
        calibrationStepText.setPadding(0, dp(10), 0, dp(10));
        alignPanel.addView(calibrationStepText, matchWrap());

        LinearLayout alignActionsOne = new LinearLayout(this);
        alignActionsOne.setOrientation(LinearLayout.HORIZONTAL);
        alignActionsOne.setGravity(Gravity.CENTER_VERTICAL);

        alignGotoButton = actionButton(R.string.calibration_align_goto);
        alignGotoButton.setOnClickListener(v -> gotoAlignmentTarget());
        alignActionsOne.addView(alignGotoButton, weightWrap(1f));

        alignOpenManualButton = actionButton(R.string.calibration_open_manual);
        alignOpenManualButton.setOnClickListener(v -> updatePageTabs(Page.MANUAL));
        alignActionsOne.addView(alignOpenManualButton, weightWrapWithLeftMargin(1f, 8));

        alignPanel.addView(alignActionsOne, matchWrap());

        LinearLayout alignActionsTwo = new LinearLayout(this);
        alignActionsTwo.setOrientation(LinearLayout.HORIZONTAL);
        alignActionsTwo.setGravity(Gravity.CENTER_VERTICAL);
        alignActionsTwo.setPadding(0, dp(8), 0, 0);

        alignAcceptButton = actionButton(R.string.calibration_align_accept);
        alignAcceptButton.setOnClickListener(v -> acceptAlignmentStar());
        alignActionsTwo.addView(alignAcceptButton, weightWrap(1f));

        alignSaveButton = actionButton(R.string.calibration_align_save);
        alignSaveButton.setOnClickListener(v -> saveAlignmentModel());
        alignActionsTwo.addView(alignSaveButton, weightWrapWithLeftMargin(1f, 8));

        alignCancelButton = actionButton(R.string.calibration_align_cancel);
        alignCancelButton.setOnClickListener(v -> cancelAlignmentSession());
        alignActionsTwo.addView(alignCancelButton, weightWrapWithLeftMargin(1f, 8));

        alignPanel.addView(alignActionsTwo, matchWrap());
        page.addView(alignPanel, matchWrap());

        page.addView(sectionTitle(R.string.calibration_tracking_section), matchWrapWithTopMargin(12));
        LinearLayout trackingPanel = card();
        TextView trackingIntro = bodyText(R.string.calibration_tracking_intro);
        trackingIntro.setPadding(0, 0, 0, dp(10));
        trackingPanel.addView(trackingIntro, matchWrap());

        modelTrackingButton = actionButton(R.string.calibration_enable_model_tracking);
        modelTrackingButton.setOnClickListener(v -> enableModelDualAxisTracking());
        trackingPanel.addView(modelTrackingButton, matchWrap());

        TextView refineIntro = bodyText(R.string.calibration_refine_intro);
        refineIntro.setPadding(0, dp(12), 0, dp(10));
        trackingPanel.addView(refineIntro, matchWrap());

        refinePaButton = actionButton(R.string.calibration_refine_pa);
        refinePaButton.setOnClickListener(v -> refinePolarAlignment());
        trackingPanel.addView(refinePaButton, matchWrap());

        page.addView(trackingPanel, matchWrap());
        return page;
    }

    private LinearLayout createSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        page.addView(sectionTitle(R.string.connection_section), matchWrap());
        page.addView(createConnectionPanel(), matchWrap());

        page.addView(sectionTitle(R.string.observer_section), matchWrapWithTopMargin(12));
        page.addView(createObserverPanel(), matchWrap());

        return page;
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

        Button bostonButton = new Button(this);
        bostonButton.setAllCaps(false);
        bostonButton.setText(R.string.use_boston);
        bostonButton.setOnClickListener(v -> applyBostonLocation());
        actions.addView(bostonButton, weightWrap(1f));

        Button gpsButton = new Button(this);
        gpsButton.setAllCaps(false);
        gpsButton.setText(R.string.use_gps);
        gpsButton.setOnClickListener(v -> requestGpsLocation());
        actions.addView(gpsButton, weightWrapWithLeftMargin(1f, 8));

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
        panel.addView(statusText, matchWrap());

        return panel;
    }

    private View createControlPanel() {
        LinearLayout panel = card();

        TextView rateLabel = labelText(R.string.rate_label);
        panel.addView(rateLabel, matchWrap());

        rateGrid = new LinearLayout(this);
        rateGrid.setOrientation(LinearLayout.VERTICAL);
        rateGrid.setPadding(0, dp(6), 0, dp(12));

        LinearLayout rateRowOne = centeredRow();
        rateRowOne.addView(rateButton(R.string.rate_guide, OnStepCommand.RATE_GUIDE.command), rateButtonParams());
        rateRowOne.addView(rateButton(R.string.rate_center, OnStepCommand.RATE_CENTER.command), rateButtonParams());
        rateGrid.addView(rateRowOne, matchWrap());

        LinearLayout rateRowTwo = centeredRow();
        rateRowTwo.addView(rateButton(R.string.rate_find, OnStepCommand.RATE_FIND.command), rateButtonParams());
        rateRowTwo.addView(rateButton(R.string.rate_slew, OnStepCommand.RATE_SLEW.command), rateButtonParams());
        rateGrid.addView(rateRowTwo, matchWrap());

        panel.addView(rateGrid, matchWrap());
        updateRateButtons();

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
                String handshake = client.connect(host, port);
                runOnUiThread(() -> {
                    connected = true;
                    busy = false;
                    activeDirection = null;
                    if (handshake.isEmpty()) {
                        setStatus(getString(R.string.status_connected_no_reply));
                        appendLog("RX <no handshake reply>");
                    } else {
                        setStatus(getString(R.string.status_connected, handshake));
                        appendLog("RX " + handshake);
                    }
                    updateUiState();
                    refreshMountPointing();
                });
            } catch (IOException ex) {
                client.close();
                runOnUiThread(() -> {
                    connected = false;
                    busy = false;
                    activeDirection = null;
                    setStatus(getString(R.string.status_connect_failed, ex.getMessage()));
                    appendLog("ERROR " + safeMessage(ex));
                    updateUiState();
                });
            }
        });
    }

    private void disconnect() {
        busy = true;
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
            }
            runOnUiThread(() -> {
                connected = false;
                busy = false;
                activeDirection = null;
                alignmentSession = null;
                setStatus(getString(R.string.status_disconnected));
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
        activeDirection = direction;
        String rate = getRateCommand();
        appendLog("TX " + rate);
        for (OnStepCommand command : direction.commands) {
            appendLog("TX " + command.command);
        }
        ioExecutor.execute(() -> {
            try {
                client.sendNoReply(rate);
                for (OnStepCommand command : direction.commands) {
                    client.sendNoReply(command.command);
                }
                runOnUiThread(() -> setStatus(getString(R.string.status_moving, direction.label(this))));
            } catch (IOException ex) {
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
    }

    private void stopMove(Direction direction) {
        if (activeDirection != direction) {
            return;
        }
        activeDirection = null;
        enqueueStop(getString(R.string.log_stop_sent));
    }

    private void enqueueStop(String logMessage) {
        enqueueCommand(OnStepCommand.STOP_ALL.command, logMessage);
    }

    private void enqueueCommand(String command, String logMessage) {
        if (!connected) {
            return;
        }
        appendLog("TX " + command);
        ioExecutor.execute(() -> {
            try {
                client.sendNoReply(command);
                runOnUiThread(() -> setStatus(logMessage));
            } catch (IOException ex) {
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
    }

    private void handleCommandFailure(IOException ex) {
        connected = false;
        busy = false;
        activeDirection = null;
        alignmentSession = null;
        client.close();
        setStatus(getString(R.string.status_command_failed, safeMessage(ex)));
        appendLog("ERROR " + safeMessage(ex));
        clearMountPointing();
        updateCalibrationViews();
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
        String raCommand = ":Sr" + formatRightAscensionCommand(target.raHours) + "#";
        String decCommand = ":Sd" + formatDeclinationCommand(target.decDegrees) + "#";
        busy = true;
        setStatus(sendingStatus);
        updateUiState();
        appendLog("TX " + raCommand);
        appendLog("TX " + decCommand);
        appendLog("TX :MS#");

        ioExecutor.execute(() -> {
            try {
                client.sendNoReply(raCommand);
                client.sendNoReply(decCommand);
                client.sendNoReply(":MS#");
                runOnUiThread(() -> {
                    busy = false;
                    setStatus(sentStatus);
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    updateUiState();
                    refreshMountPointing();
                });
            } catch (IOException ex) {
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
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

        ioExecutor.execute(() -> {
            try {
                for (String command : commands) {
                    client.sendNoReply(command);
                }
                runOnUiThread(() -> {
                    busy = false;
                    setStatus(getString(R.string.status_sync_mount_sent));
                    updateUiState();
                });
            } catch (IOException ex) {
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
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
        SkyChartView.Target target = suggestions.get(suggestedCalibrationIndex % suggestions.size());
        suggestedCalibrationIndex++;
        setCalibrationTarget(target);
        setCalibrationStatus(getString(
                R.string.calibration_suggested_status,
                target.label,
                formatRightAscensionDisplay(target.raHours),
                formatDeclinationDisplay(target.decDegrees)
        ));
    }

    private void showCalibrationTargetInSky() {
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
        setCalibrationTarget(target);
        applySkyTarget(target, true);
    }

    private void quickGotoCalibrationTarget() {
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
        setCalibrationTarget(target);
        selectSkyTarget(target, true);
        sendGotoTarget(
                target,
                getString(R.string.calibration_quick_goto_sending, target.label),
                getString(R.string.calibration_quick_goto_sent, target.label),
                () -> setCalibrationStatus(getString(R.string.calibration_quick_center_prompt, target.label))
        );
    }

    private void syncQuickCalibrationTarget() {
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
        setCalibrationTarget(target);
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.noReply(":Sr" + formatRightAscensionCommand(target.raHours) + "#"));
        commands.add(MountCommand.noReply(":Sd" + formatDeclinationCommand(target.decDegrees) + "#"));
        commands.add(MountCommand.withReply(OnStepCommand.SYNC_CURRENT_TARGET.command));
        commands.add(MountCommand.noReply(OnStepCommand.TRACK_SIDEREAL.command));
        commands.add(MountCommand.withReply(OnStepCommand.TRACK_ENABLE.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_quick_sync_sending, target.label),
                getString(R.string.calibration_quick_sync_sent, target.label),
                () -> {
                    setCalibrationStatus(getString(R.string.calibration_quick_sync_sent, target.label));
                    refreshMountPointing();
                }
        );
    }

    private void startAlignment(int starCount) {
        if (!connected || busy || alignmentSession != null) {
            return;
        }
        OnStepCommand startCommand;
        if (starCount == 1) {
            startCommand = OnStepCommand.ALIGN_ONE;
        } else if (starCount == 2) {
            startCommand = OnStepCommand.ALIGN_TWO;
        } else {
            startCommand = OnStepCommand.ALIGN_THREE;
        }
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(startCommand.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_align_starting, starCount),
                getString(R.string.calibration_align_started, starCount),
                () -> {
                    alignmentSession = new AlignmentSession(starCount);
                    calibrationTarget = null;
                    calibrationTargetField.setText("");
                    setCalibrationStatus(getString(R.string.calibration_align_started, starCount));
                    updateCalibrationViews();
                    fillSuggestedCalibrationTarget();
                }
        );
    }

    private void gotoAlignmentTarget() {
        if (alignmentSession == null || alignmentSession.isComplete()) {
            return;
        }
        SkyChartView.Target target = resolveCalibrationTarget();
        if (target == null) {
            return;
        }
        setCalibrationTarget(target);
        alignmentSession.currentTarget = target;
        selectSkyTarget(target, true);
        sendGotoTarget(
                target,
                getString(R.string.calibration_align_goto_sending, alignmentSession.currentStarNumber(), alignmentSession.totalStars, target.label),
                getString(R.string.calibration_align_goto_sent, target.label),
                () -> {
                    setCalibrationStatus(getString(R.string.calibration_align_center_prompt, target.label));
                    updateCalibrationViews();
                }
        );
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
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.withReply(OnStepCommand.ALIGN_ACCEPT.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_align_accepting, acceptedTarget.label),
                getString(R.string.calibration_align_accepted, acceptedTarget.label),
                () -> {
                    alignmentSession.acceptedStars++;
                    if (alignmentSession.isComplete()) {
                        setCalibrationStatus(getString(R.string.calibration_align_complete, alignmentSession.totalStars));
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
                    updateCalibrationViews();
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
                () -> setCalibrationStatus(getString(R.string.calibration_align_saved))
        );
    }

    private void cancelAlignmentSession() {
        alignmentSession = null;
        calibrationTarget = null;
        setCalibrationStatus(getString(R.string.calibration_align_cancelled));
        updateCalibrationViews();
    }

    private void enableModelDualAxisTracking() {
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.noReply(OnStepCommand.TRACK_SIDEREAL.command));
        commands.add(MountCommand.withReply(OnStepCommand.TRACK_FULL_COMPENSATION.command));
        commands.add(MountCommand.withReply(OnStepCommand.TRACK_DUAL_AXIS.command));
        commands.add(MountCommand.withReply(OnStepCommand.TRACK_ENABLE.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_tracking_sending),
                getString(R.string.calibration_tracking_sent),
                () -> setCalibrationStatus(getString(R.string.calibration_tracking_sent))
        );
    }

    private void refinePolarAlignment() {
        List<MountCommand> commands = new ArrayList<>();
        commands.add(MountCommand.noReply(OnStepCommand.REFINE_POLAR_ALIGNMENT.command));
        runMountCommands(
                commands,
                getString(R.string.calibration_refine_sending),
                getString(R.string.calibration_refine_sent),
                () -> setCalibrationStatus(getString(R.string.calibration_refine_sent))
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
        updateUiState();
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

        ioExecutor.execute(() -> {
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
                runOnUiThread(() -> handleCommandFailure(ex));
            }
        });
    }

    private void refreshMountPointing() {
        if (!connected || busy) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                String raReply = client.query(":GR#");
                String decReply = client.query(":GD#");
                double raHours = parseRightAscension(raReply);
                double decDegrees = parseDeclination(decReply);
                runOnUiThread(() -> setMountPointing(raHours, decDegrees));
            } catch (IOException | IllegalArgumentException ex) {
                runOnUiThread(() -> handleCommandFailure(ex instanceof IOException
                        ? (IOException) ex
                        : new IOException(ex.getMessage(), ex)));
            }
        });
    }

    private void setMountPointing(double raHours, double decDegrees) {
        if (skyChartView != null) {
            skyChartView.setMountEquatorial(raHours, decDegrees);
        }
        if (mountPointingText != null) {
            mountPointingText.setText(getString(
                    R.string.mount_pointing_status,
                    formatRightAscensionDisplay(raHours),
                    formatDeclinationDisplay(decDegrees)
            ));
        }
    }

    private void clearMountPointing() {
        if (skyChartView != null) {
            skyChartView.clearMountEquatorial();
        }
        if (mountPointingText != null) {
            mountPointingText.setText(R.string.mount_pointing_default);
        }
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
        updateUiState();
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

    private static String formatDeclinationDisplay(double decDegrees) {
        String command = formatDeclinationCommand(decDegrees);
        return command.replace('*', '°');
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

    private String getRateCommand() {
        return selectedRateCommand;
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

    private Button rateButton(int labelRes, String command) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(labelRes);
        button.setTextSize(14);
        button.setTag(command);
        button.setMinHeight(dp(46));
        button.setOnClickListener(v -> {
            selectedRateCommand = command;
            updateRateButtons();
            if (connected) {
                enqueueCommand(selectedRateCommand, getString(R.string.log_rate_changed));
            }
        });
        rateButtons.add(button);
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

    private LinearLayout card() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackgroundColor(Color.WHITE);
        return panel;
    }

    private TextView titleText(int textRes, int sp) {
        TextView textView = new TextView(this);
        textView.setText(textRes);
        textView.setTextSize(sp);
        textView.setTextColor(Color.rgb(17, 24, 39));
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
        textView.setTextColor(Color.rgb(31, 41, 55));
        textView.setGravity(Gravity.START);
        return textView;
    }

    private TextView bodyText(int textRes) {
        TextView textView = new TextView(this);
        textView.setText(textRes);
        textView.setTextSize(15);
        textView.setTextColor(Color.rgb(75, 85, 99));
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

    private LinearLayout.LayoutParams sideMenuParams() {
        return new LinearLayout.LayoutParams(
                dp(sideMenuExpanded ? 82 : 44),
                LinearLayout.LayoutParams.MATCH_PARENT
        );
    }

    private LinearLayout.LayoutParams sideMenuToggleParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
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

    private LinearLayout.LayoutParams rateButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
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
        drawable.setColor(enabled ? Color.WHITE : Color.rgb(249, 250, 251));
        drawable.setStroke(dp(1), enabled ? Color.rgb(75, 85, 99) : Color.rgb(229, 231, 235));
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
            drawable.setColor(Color.rgb(249, 250, 251));
            drawable.setStroke(dp(1), Color.rgb(229, 231, 235));
        } else if (selected) {
            drawable.setColor(Color.rgb(224, 242, 254));
            drawable.setStroke(dp(2), Color.rgb(14, 116, 144));
        } else {
            drawable.setColor(Color.WHITE);
            drawable.setStroke(dp(1), Color.rgb(156, 163, 175));
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
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        if (statusText != null) {
            statusText.setText(text);
        }
        if (manualStatusText != null) {
            manualStatusText.setText(text);
        }
    }

    private void appendLog(String line) {
        logLines.addLast(line);
        while (logLines.size() > 12) {
            logLines.removeFirst();
        }

        StringBuilder builder = new StringBuilder();
        for (String logLine : logLines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(logLine);
        }
        logText.setText(builder.length() == 0 ? getString(R.string.log_empty) : builder.toString());
    }

    private void updateUiState() {
        hostField.setEnabled(!connected && !busy);
        portField.setEnabled(!connected && !busy);
        connectButton.setEnabled(!connected && !busy);
        disconnectButton.setEnabled(connected && !busy);
        connectionForm.setVisibility(connected ? View.GONE : View.VISIBLE);
        connectButton.setVisibility(connected ? View.GONE : View.VISIBLE);
        disconnectButton.setVisibility(connected ? View.VISIBLE : View.GONE);
        updateRateButtons();
        if (gotoButton != null) {
            gotoButton.setEnabled(!busy);
            gotoButton.setText(connected ? R.string.sky_goto_target : R.string.sky_find_target);
        }
        if (syncMountButton != null) {
            syncMountButton.setEnabled(connected && !busy);
        }
        if (calibrationSuggestButton != null) {
            calibrationSuggestButton.setEnabled(skyChartView != null);
        }
        if (calibrationShowButton != null) {
            calibrationShowButton.setEnabled(skyChartView != null && !busy);
        }
        if (quickGotoButton != null) {
            quickGotoButton.setEnabled(connected && !busy);
        }
        if (quickSyncButton != null) {
            quickSyncButton.setEnabled(connected && !busy);
        }
        boolean canStartAlignment = connected && !busy && alignmentSession == null;
        if (alignOneButton != null) {
            alignOneButton.setEnabled(canStartAlignment);
        }
        if (alignTwoButton != null) {
            alignTwoButton.setEnabled(canStartAlignment);
        }
        if (alignThreeButton != null) {
            alignThreeButton.setEnabled(canStartAlignment);
        }
        boolean alignmentActive = connected && !busy && alignmentSession != null && !alignmentSession.isComplete();
        if (alignGotoButton != null) {
            alignGotoButton.setEnabled(alignmentActive);
        }
        if (alignAcceptButton != null) {
            alignAcceptButton.setEnabled(alignmentActive);
        }
        if (alignOpenManualButton != null) {
            alignOpenManualButton.setEnabled(alignmentSession != null);
        }
        if (alignSaveButton != null) {
            alignSaveButton.setEnabled(connected && !busy && alignmentSession != null && alignmentSession.isComplete());
        }
        if (alignCancelButton != null) {
            alignCancelButton.setEnabled(alignmentSession != null && !busy);
        }
        if (modelTrackingButton != null) {
            modelTrackingButton.setEnabled(connected && !busy);
        }
        if (refinePaButton != null) {
            refinePaButton.setEnabled(connected && !busy);
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
    }

    private void setObserverMessage(String message) {
        if (observerStatusText != null) {
            observerStatusText.setText(message);
        }
    }

    private void updatePageTabs(Page selectedPage) {
        if (manualPage != null) {
            manualPage.setVisibility(selectedPage == Page.MANUAL ? View.VISIBLE : View.GONE);
        }
        if (skyPage != null) {
            skyPage.setVisibility(selectedPage == Page.SKY ? View.VISIBLE : View.GONE);
        }
        if (settingsPage != null) {
            settingsPage.setVisibility(selectedPage == Page.SETTINGS ? View.VISIBLE : View.GONE);
        }
        if (calibrationPage != null) {
            calibrationPage.setVisibility(selectedPage == Page.CALIBRATION ? View.VISIBLE : View.GONE);
        }
        styleTabButton(manualTabButton, selectedPage == Page.MANUAL);
        styleTabButton(skyTabButton, selectedPage == Page.SKY);
        styleTabButton(settingsTabButton, selectedPage == Page.SETTINGS);
        styleTabButton(calibrationTabButton, selectedPage == Page.CALIBRATION);
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
        int horizontalPadding = expanded ? 8 : 4;
        sideMenu.setPadding(dp(horizontalPadding), dp(22), dp(horizontalPadding), dp(8));

        int menuItemVisibility = expanded ? View.VISIBLE : View.GONE;
        if (settingsTabButton != null) {
            settingsTabButton.setVisibility(menuItemVisibility);
        }
        if (calibrationTabButton != null) {
            calibrationTabButton.setVisibility(menuItemVisibility);
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
    }

    private void styleTabButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setTextColor(selected ? Color.rgb(15, 23, 42) : Color.rgb(226, 232, 240));
        button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setBackground(createTabBackground(selected));
    }

    private void updateRateButtons() {
        boolean enabled = connected && !busy;
        for (Button button : rateButtons) {
            String command = (String) button.getTag();
            boolean selected = command.equals(selectedRateCommand);
            button.setEnabled(enabled);
            button.setTextColor(selected ? Color.rgb(14, 116, 144) : Color.rgb(55, 65, 81));
            button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            button.setBackground(createRateButtonBackground(selected, enabled));
        }
    }

    private void setDirectionButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setTextColor(enabled ? Color.rgb(17, 24, 39) : Color.rgb(156, 163, 175));
        button.setBackground(createDirectionButtonBackground(enabled));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private static final class AlignmentSession {
        final int totalStars;
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
        RATE_SLEW(":RS#"),
        MOVE_NORTH(":Mn#"),
        MOVE_SOUTH(":Ms#"),
        MOVE_EAST(":Me#"),
        MOVE_WEST(":Mw#"),
        SYNC_CURRENT_TARGET(":CM#"),
        ALIGN_ONE(":A1#"),
        ALIGN_TWO(":A2#"),
        ALIGN_THREE(":A3#"),
        ALIGN_ACCEPT(":A+#"),
        ALIGN_WRITE(":AW#"),
        TRACK_SIDEREAL(":TQ#"),
        TRACK_ENABLE(":Te#"),
        TRACK_FULL_COMPENSATION(":To#"),
        TRACK_DUAL_AXIS(":T2#"),
        REFINE_POLAR_ALIGNMENT(":MP#"),
        STOP_ALL(":Q#");

        private final String command;

        OnStepCommand(String command) {
            this.command = command;
        }
    }

    private enum Page {
        MANUAL,
        CALIBRATION,
        SKY,
        SETTINGS
    }

    private enum Direction {
        NORTH(R.string.direction_north, OnStepCommand.MOVE_NORTH),
        NORTH_EAST(R.string.direction_north_east, OnStepCommand.MOVE_NORTH, OnStepCommand.MOVE_EAST),
        EAST(R.string.direction_east, OnStepCommand.MOVE_EAST),
        SOUTH_EAST(R.string.direction_south_east, OnStepCommand.MOVE_SOUTH, OnStepCommand.MOVE_EAST),
        SOUTH(R.string.direction_south, OnStepCommand.MOVE_SOUTH),
        SOUTH_WEST(R.string.direction_south_west, OnStepCommand.MOVE_SOUTH, OnStepCommand.MOVE_WEST),
        WEST(R.string.direction_west, OnStepCommand.MOVE_WEST),
        NORTH_WEST(R.string.direction_north_west, OnStepCommand.MOVE_NORTH, OnStepCommand.MOVE_WEST);

        private final int labelRes;
        private final OnStepCommand[] commands;

        Direction(int labelRes, OnStepCommand... commands) {
            this.labelRes = labelRes;
            this.commands = commands;
        }

        private String label(Activity activity) {
            return activity.getString(labelRes);
        }
    }
}
