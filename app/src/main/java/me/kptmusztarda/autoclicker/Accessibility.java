package me.kptmusztarda.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.Objects;

import me.kptmusztarda.autoclicker.gestures.Dispatchable;
import me.kptmusztarda.autoclicker.gestures.Gesture;
import me.kptmusztarda.autoclicker.gestures.Gestures;
import me.kptmusztarda.autoclicker.gestures.RandomCircle;
import me.kptmusztarda.handylib.Logger;

public class Accessibility extends AccessibilityService {

    private static final String TAG = "Accessibility";
    public static final String ACTION_SHOW = "me.kptmusztarda.autoclicker.ACTION_SHOW";
    public static final String ACTION_CLOSE = "me.kptmusztarda.autoclicker.ACTION_CLOSE";
    public static final String ACTION_LOAD = "me.kptmusztarda.autoclicker.ACTION_LOAD";
    public static final String EXTRA_PROFILE_ID = "me.kptmusztarda.autoclicker.EXTRA_PROFILE_ID";

    private MyWindowManager windowManager;
    private SettingsLayout settingsLayout;
    private BackgroundView backgroundView;
    private DimView dimView;
    private ImageButton mainButton, editButton, addButton, removeButton, dimButton, closeButton, increaseRadiusButton, decreaseRadiusButton;
    private View divider;

    private ViewsManager viewsManager;
    private ProfileManager profileManager;
    private GestureDispatcher gestureDispatcher;
    private Profile profile;
    private int profileID;

    private boolean active = false;
    private boolean editMode = false;
    private boolean dimmed = false;
    private static  boolean isOpened = false;
    private long volumeDownPressTime;

    private boolean savedState[] = new boolean[2];


    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Logger.log(TAG, "Received: " + intent.getAction());

            switch (intent.getAction()) {
                case ACTION_LOAD:

                    show();
                    int id = intent.getIntExtra(EXTRA_PROFILE_ID, 0);
                    loadProfile(id);

                    break;
                case ACTION_CLOSE:

                    gestureDispatcher.stop();
                    close();

                    break;
                case Intent.ACTION_SCREEN_OFF:

                    gestureDispatcher.stop();
                    dim(false);

                    break;
                case TelephonyManager.ACTION_PHONE_STATE_CHANGED:

                    Logger.log(TAG, "Extra State: " + intent.getStringExtra(TelephonyManager.EXTRA_STATE));
                    onPhoneStateChanged(context);
                    break;
            }
        }
    };

    private void onPhoneStateChanged(Context context) {

        TelephonyManager  telephonyManager = (TelephonyManager) context.getSystemService(TELEPHONY_SERVICE);
        switch (telephonyManager.getCallState()) {
            case TelephonyManager.CALL_STATE_RINGING:
                savedState[0] = active;
                savedState[1] = dimmed;
                gestureDispatcher.stop();
                dim(false);

                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:

                savedState[0] = false;
                savedState[1] = false;

                break;
            case TelephonyManager.CALL_STATE_IDLE:
                if(savedState[0])
                    new Handler().postDelayed(() -> {
                        gestureDispatcher.start();
                        dim(savedState[1]);
                    }, 2000);

                break;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.log(TAG, "onCreate");
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CLOSE);
        filter.addAction(ACTION_LOAD);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        registerReceiver(receiver, filter);
        Logger.log(TAG, "BroadcastReceiver registered");

        viewsManager = ViewsManager.getInstance();
        setupViews();
        profileManager = new ProfileManager(this);

        gestureDispatcher = new GestureDispatcher() {
            @Override
            void dispatch(GestureDescription gesture) {
                dispatchGesture(gesture, null, null);
            }

            @Override
            void start() {
                super.start();
                settingsLayout.setMainSwitchColorToActive(true);
            }

            @Override
            void stop() {
                super.stop();
                settingsLayout.setMainSwitchColorToActive(false);
            }
        };
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupViews() {
        windowManager = new MyWindowManager(getApplicationContext());

        backgroundView = new BackgroundView(this);
        viewsManager.setBackgroundView(backgroundView);

        settingsLayout = new SettingsLayout(this);
        viewsManager.setSettingsLayout(settingsLayout);

        dimView = new DimView(this);

        mainButton = settingsLayout.findViewById(R.id.mainButton);
        mainButton.setOnTouchListener((v, event) -> {
            switch(event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    Logger.log(TAG, "Button clicked");
                    if(!gestureDispatcher.isActive()) {
                        if(editMode) enableEditMode(false);
                        gestureDispatcher.start();
                    } else gestureDispatcher.stop();
                    break;
            }
            return false;
        });
        settingsLayout.setMainSwitchColorToActive(false);

        editButton = settingsLayout.findViewById(R.id.editButton);
        editButton.setOnTouchListener((v, event) -> {
            switch(event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if(editMode) enableEditMode(false);
                    else if(gestureDispatcher.isActive()) {
                        gestureDispatcher.stop();
                        enableEditMode(true);
                    } else enableEditMode(true);
                    break;
            }
            return false;
        });

        addButton = settingsLayout.findViewById(R.id.addButton);
        addButton.setOnClickListener(v -> {

            LayoutInflater inflater = (LayoutInflater)
                    getSystemService(LAYOUT_INFLATER_SERVICE);
            View popupView = inflater.inflate(R.layout.new_gesture_popup, null);

            int width = LinearLayout.LayoutParams.WRAP_CONTENT;
            int height = LinearLayout.LayoutParams.WRAP_CONTENT;
            PopupWindow popupWindow = new PopupWindow(popupView, width, height, true);

            popupWindow.showAtLocation(backgroundView, Gravity.CENTER, 0, 0);

            Spinner spinner = popupView.findViewById(R.id.new_gesture_list);
            spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Gestures.getNamesList()));

            Button cancel = popupView.findViewById(R.id.new_gesture_cancel);
            final boolean cancelDown[] = {false};
            cancel.setOnTouchListener((v12, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelDown[0] = true;
                        break;
                    case MotionEvent.ACTION_UP:
                        if(cancelDown[0]) popupWindow.dismiss();
                        cancelDown[0] = false;
                        break;
                }
                return false;
            });

            Button confirm = popupView.findViewById(R.id.new_gesture_confirm);
            final boolean cancelUp[] = {false};
            confirm.setOnTouchListener((v12, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelUp[0] = true;
                        break;
                    case MotionEvent.ACTION_UP:
                        if(cancelUp[0]) {

                            int type = (int) spinner.getSelectedItemId();

                            int coords[] = new int[2];
                            v.getLocationOnScreen(coords);
                            profile.addGesture(type, coords[0] + v.getWidth() + 100, coords[1], 0, true);

                        }
                        cancelUp[0] = false;
                        break;
                }
                return false;
            });
        });
        addButton.setVisibility(View.GONE);

        removeButton = settingsLayout.findViewById(R.id.removeButton);
        removeButton.setOnClickListener(v -> {
            profile.removePoint(profile.getSelectedGestureIndex());
            backgroundView.invalidate();
        });
        removeButton.setVisibility(View.GONE);

        dimButton = settingsLayout.findViewById(R.id.dimButton);
        dimButton.setOnTouchListener((v, event) -> {
            switch(event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dim();
                    break;
            }
            return false;
        });

        closeButton = settingsLayout.findViewById(R.id.closeButton);
        closeButton.setOnTouchListener((v, event) -> {
            switch(event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    close();
                    break;
            }
            return false;
        });

        divider = settingsLayout.findViewById(R.id.divider);
        divider.setVisibility(View.GONE);

        increaseRadiusButton = settingsLayout.findViewById(R.id.increaseRadiusButton);
        increaseRadiusButton.setOnClickListener(v -> {
                RandomCircle p = (RandomCircle) profile.getSelectedGesture();
                p.setRadius(p.getRadius() + 10);
                backgroundView.invalidate();

        });
        increaseRadiusButton.setVisibility(View.GONE);

        decreaseRadiusButton = settingsLayout.findViewById(R.id.decreaseRadiusButtonimageButton2);
        decreaseRadiusButton.setOnClickListener(v -> {
            RandomCircle p = (RandomCircle) profile.getSelectedGesture();
            p.setRadius(p.getRadius() - 10);
            backgroundView.invalidate();
        });
        decreaseRadiusButton.setVisibility(View.GONE);
    }

    private void loadProfile(int profileID) {

        if(profile != null) enableEditMode(false); //saves previous profile and removes views

        profileManager.loadProfiles();
        this.profileID = profileID;
        profile = profileManager.getProfile(profileID);

        Logger.log(TAG, "Loading profile " + profileID + " named \"" + profile.getName() + "\"");

        gestureDispatcher.setProfile(profile);
        backgroundView.setProfile(profile);
    }



    @Override
    public boolean onKeyEvent(KeyEvent event) {
//        Logger.log(TAG, "Keycode " + event.getKeyCode());
        if(dimmed) {
            if ((event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    volumeDownPressTime = System.currentTimeMillis();
//                    Logger.log(TAG, "Down");
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
//                    Logger.log(TAG, "Up");
                    if (System.currentTimeMillis() - volumeDownPressTime > 500) {
                        dim();
                    } else {
                        AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                        Objects.requireNonNull(audioManager).adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
//                        return super.onKeyEvent(event);
                    }
                }
                return true;
            } else return super.onKeyEvent(event);
        } else return super.onKeyEvent(event);
    }

    private void enableEditMode(boolean b) {

        if(b) {
            windowManager.addView(backgroundView, backgroundView.getParams());
            for(Dispatchable view : profile.getGestures()) {
                view.show();
            }
            addButton.setVisibility(View.VISIBLE);
            removeButton.setVisibility(View.VISIBLE);
            divider.setVisibility(View.VISIBLE);
            increaseRadiusButton.setVisibility(View.VISIBLE);
            decreaseRadiusButton.setVisibility(View.VISIBLE);
        } else {

            profileManager.saveProfile(profileID);

            for(int i = profile.getGestures().size()-1; i>=0; i--) {
                profile.getGestures().get(i).hide();
            }
            windowManager.removeViewImmediate(backgroundView);

            addButton.setVisibility(View.GONE);
            removeButton.setVisibility(View.GONE);
            divider.setVisibility(View.GONE);
            increaseRadiusButton.setVisibility(View.GONE);
            decreaseRadiusButton.setVisibility(View.GONE);
        }
        editMode = b;

//        windowManager.updateViewLayout(settingsLayout, settingsLayout.getParams());
    }


    private void dim() {
        if(dimmed) {
            windowManager.removeViewImmediate(dimView);
            dimmed = false;
        } else {
            windowManager.addView(dimView, dimView.getParams());
            dimmed = true;
        }
    }

    private void dim(boolean b) {
        if(b) {
            windowManager.addView(dimView, dimView.getParams());
            dimmed = true;
        } else {
            windowManager.removeViewImmediate(dimView);
            dimmed = false;
        }
    }
    private void close() {
        if(editMode) enableEditMode(false);
        gestureDispatcher.stop();
        dim(false);
        windowManager.removeViewImmediate(settingsLayout);
        isOpened = false;
    }

    private void show() {
        windowManager.addView(settingsLayout, settingsLayout.getParams());
        isOpened = true;
    }

    @Override
    public void onInterrupt() {

    }

    protected static boolean isOpened() {
        return isOpened;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        close();
        unregisterReceiver(receiver);
        Logger.log(TAG, "BroadcastReceiver unregistered");
    }
}