/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.weatherstation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import com.google.android.things.contrib.driver.apa102.Apa102;
import com.google.android.things.contrib.driver.bmx280.Bmx280SensorDriver;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay;
import com.google.android.things.contrib.driver.pwmspeaker.Speaker;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.android.things.contrib.driver.rainbowhat.RainbowHat;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class WeatherStationActivity extends Activity {

    private static final String TAG = WeatherStationActivity.class.getSimpleName();

    private enum AppMode {
        KNIGHTRIGER,
        HOME,
        MARIO
    }

    private SensorManager mSensorManager;

    private ButtonInputDriver mButtonAInputDriver;
    private ButtonInputDriver mButtonBInputDriver;
    private ButtonInputDriver mButtonCInputDriver;

    private Bmx280SensorDriver mEnvironmentalSensorDriver;
    private AlphanumericDisplay mDisplay;
    private AppMode mAppMode;

     private Thread ledThread;
    private Thread displayThread;


    private Apa102 mLedstrip;
    private int[] mRainbow = new int[7];
    private static final int LEDSTRIP_BRIGHTNESS = 30;
    private static final float BAROMETER_RANGE_LOW = 965.f;
    private static final float BAROMETER_RANGE_HIGH = 1035.f;
    private static final float BAROMETER_RANGE_SUNNY = 1010.f;
    private static final float BAROMETER_RANGE_RAINY = 990.f;

    private Gpio aButtonLed;
    private Gpio bButtonLed;
    private Gpio cButtonLed;


    private int SPEAKER_READY_DELAY_MS = 300;
    private Speaker mSpeaker;

    private float mLastTemperature;
    private float mLastPressure;

    private PubsubPublisher mPubsubPublisher;
    private ImageView mImageView;

    // Callback used when we register the BMP280 sensor driver with the system's SensorManager.
    private SensorManager.DynamicSensorCallback mDynamicSensorCallback
            = new SensorManager.DynamicSensorCallback() {
        @Override
        public void onDynamicSensorConnected(Sensor sensor) {
            if (sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE) {
                // Our sensor is connected. Start receiving temperature data.
                mSensorManager.registerListener(mTemperatureListener, sensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
                if (mPubsubPublisher != null) {
                    mSensorManager.registerListener(mPubsubPublisher.getTemperatureListener(), sensor,
                            SensorManager.SENSOR_DELAY_NORMAL);
                }
            } else if (sensor.getType() == Sensor.TYPE_PRESSURE) {
                // Our sensor is connected. Start receiving pressure data.
                mSensorManager.registerListener(mPressureListener, sensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
                if (mPubsubPublisher != null) {
                    mSensorManager.registerListener(mPubsubPublisher.getPressureListener(), sensor,
                            SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        }

        @Override
        public void onDynamicSensorDisconnected(Sensor sensor) {
            super.onDynamicSensorDisconnected(sensor);
        }
    };

    // Callback when SensorManager delivers temperature data.
    private SensorEventListener mTemperatureListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mLastTemperature = event.values[0];
            //Log.d(TAG, "sensor changed: " + mLastTemperature);

        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            //Log.d(TAG, "accuracy changed: " + accuracy);
        }
    };

    // Callback when SensorManager delivers pressure data.
    private SensorEventListener mPressureListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mLastPressure = event.values[0];
            //Log.d(TAG, "sensor changed: " + mLastPressure);
            updateBarometer(mLastPressure);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            //Log.d(TAG, "accuracy changed: " + accuracy);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Started Weather Station");

        setContentView(R.layout.activity_main);
        mImageView = (ImageView) findViewById(R.id.imageView);

        mSensorManager = ((SensorManager) getSystemService(SENSOR_SERVICE));

        // GPIO button that generates 'A' keypresses (handled by onKeyUp method)
        try {
            mButtonAInputDriver = new ButtonInputDriver(RainbowHat.BUTTON_A,
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_A);
            mButtonAInputDriver.register();
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_A");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing GPIO button", e);
        }

        try {
            mButtonBInputDriver = new ButtonInputDriver(RainbowHat.BUTTON_B,
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_B);
            mButtonBInputDriver.register();
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_B");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing GPIO button", e);
        }

        try {
            mButtonCInputDriver = new ButtonInputDriver(RainbowHat.BUTTON_C,
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_C);
            mButtonCInputDriver.register();
            Log.d(TAG, "Initialized GPIO Button that generates a keypress with KEYCODE_C");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing GPIO button", e);
        }

        // I2C
        // Note: In this sample we only use one I2C bus, but multiple peripherals can be connected
        // to it and we can access them all, as long as they each have a different address on the
        // bus. Many peripherals can be configured to use a different address, often by connecting
        // the pins a certain way; this may be necessary if the default address conflicts with
        // another peripheral's. In our case, the temperature sensor and the display have
        // different default addresses, so everything just works.
        try {
            mEnvironmentalSensorDriver = new Bmx280SensorDriver(BoardDefaults.getI2cBus());
            mSensorManager.registerDynamicSensorCallback(mDynamicSensorCallback);
            mEnvironmentalSensorDriver.registerTemperatureSensor();
            mEnvironmentalSensorDriver.registerPressureSensor();
            Log.d(TAG, "Initialized I2C BMP280");
        } catch (IOException e) {
            throw new RuntimeException("Error initializing BMP280", e);
        }

        try {
            mDisplay = new AlphanumericDisplay(BoardDefaults.getI2cBus());
            mDisplay.setEnabled(true);
            mDisplay.clear();
            updateAppMode(AppMode.HOME);
            Log.d(TAG, "Initialized I2C Display");
        } catch (IOException e) {
            Log.e(TAG, "Error initializing display", e);
            mDisplay = null;
        }

        // SPI ledstrip
        try {
            mLedstrip = new Apa102(BoardDefaults.getSpiBus(), Apa102.Mode.BGR);
            mLedstrip.setBrightness(LEDSTRIP_BRIGHTNESS);
        } catch (IOException e) {
            mLedstrip = null; // Led strip is optional.
        }

        try {
            bButtonLed = RainbowHat.openLed(RainbowHat.LED_GREEN);
            cButtonLed = RainbowHat.openLed(RainbowHat.LED_BLUE);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing led", e);
        }
        // GPIO led
        try {
            PeripheralManagerService pioService = new PeripheralManagerService();
            aButtonLed = pioService.openGpio(BoardDefaults.getLedGpioPin());
            aButtonLed.setEdgeTriggerType(Gpio.EDGE_NONE);
            aButtonLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            aButtonLed.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing led", e);
        }

        // PWM speaker
        try {
            mSpeaker = RainbowHat.openPiezo();
            final ValueAnimator slide = ValueAnimator.ofFloat(440, 440 * 4);
            slide.setDuration(50);
            slide.setRepeatCount(5);
            slide.setInterpolator(new LinearInterpolator());
            slide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    try {
                        float v = (float) animation.getAnimatedValue();
                        mSpeaker.play(v);
                    } catch (IOException e) {
                        throw new RuntimeException("Error sliding speaker", e);
                    }
                }
            });
            slide.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        mSpeaker.stop();
                    } catch (IOException e) {
                        throw new RuntimeException("Error sliding speaker", e);
                    }
                }
            });
            Handler handler = new Handler(getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    slide.start();
                }
            }, SPEAKER_READY_DELAY_MS);
        } catch (IOException e) {
            throw new RuntimeException("Error initializing speaker", e);
        }

        // start Cloud PubSub Publisher if cloud credentials are present.
        int credentialId = getResources().getIdentifier("credentials", "raw", getPackageName());
        if (credentialId != 0) {
            try {
                mPubsubPublisher = new PubsubPublisher(this, "weatherstation",
                        BuildConfig.PROJECT_ID, BuildConfig.PUBSUB_TOPIC, credentialId);
                mPubsubPublisher.start();
            } catch (IOException e) {
                Log.e(TAG, "error creating pubsub publisher", e);
            }
        }
    }

    enum LedStripDirection {
        Forth,
        Back
    }


    private void startMarioMode() {
        Log.d(TAG,"Start Home Mode");
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                while (mAppMode == AppMode.MARIO) {
                    floatTextOnDisplay("MARIO MODE",AppMode.MARIO);
                }
            }
        };
        displayThread =  new Thread(runnable);
        displayThread.start();

        Runnable speakerRunnable = new Runnable() {
            @Override
            public void run() {
                playMarioTheme();
            }
        };
        new Thread(speakerRunnable).start();
    }

    private void startHomeMode() {
        Log.d(TAG,"Start Home Mode");
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                while (mAppMode == AppMode.HOME) {
                    floatTextOnDisplay("A=HOME, B=KNIGHT RIDER MODE, C=MARIO MODE.",AppMode.HOME);
                }
            }
        };
        displayThread =  new Thread(runnable);
        displayThread.start();
    }

    public void knightRiderLeds() {
        LedStripDirection direction = LedStripDirection.Forth;
        int numberOfLights = 2;
        List<Integer> activeLeds = new ArrayList<Integer>();
        int numberOfStates = mRainbow.length + numberOfLights;
        while (mAppMode == AppMode.KNIGHTRIGER) {
            for (int i = 0; i < numberOfStates; i++) {
                activeLeds.clear();
                for(int j = 0; j< numberOfLights;j++) {
                    int ledIndex = 0;
                    switch (direction) {
                        case Forth:
                            ledIndex = i + j - 2;
                            break;
                        case Back:
                            ledIndex = numberOfStates - i + j - 2;
                            break;
                    }
                    if (ledIndex >= 0 && ledIndex < mRainbow.length) {
                        activeLeds.add(ledIndex);
                    }
                }
                exclusivlyActivateLeds(activeLeds, Color.RED);
                try {
                    Thread.currentThread().sleep(100);
                } catch (InterruptedException e) {
                    Log.e(TAG, "InterruptedException");
                }
            }
            if (direction == LedStripDirection.Forth) {
                direction = LedStripDirection.Back;
            } else {
                direction = LedStripDirection.Forth;
            }
        }
        activeLeds.clear();
        exclusivlyActivateLeds(activeLeds,Color.TRANSPARENT);
    }

    public synchronized void exclusivlyActivateLeds(List<Integer> leds, int color) {
        for (int i = 0; i < mRainbow.length; i++) {
            mRainbow[i] = Color.TRANSPARENT;
        }

        for (int ledIndex : leds) {
            mRainbow[ledIndex] = color;
        }
        try {
            mLedstrip.write(mRainbow);
        } catch (IOException e) {
            Log.e(TAG, "IOException");
        }
    }

    private void playMarioTheme() {
        if(mSpeaker == null) {
            return;
        }

        List<Integer> frequencies = new ArrayList<Integer>();
        frequencies.add(660);
        frequencies.add(660);
        frequencies.add(660);
        frequencies.add(510);
        frequencies.add(660);
        frequencies.add(770);
        frequencies.add(380);

        frequencies.add(510);
        frequencies.add(380);
        frequencies.add(320);
        frequencies.add(440);
        frequencies.add(480);
        frequencies.add(450);
        frequencies.add(430);
        frequencies.add(380);
        frequencies.add(660);
        frequencies.add(760);
        frequencies.add(860);
        frequencies.add(700);
        frequencies.add(760);
        frequencies.add(660);
        frequencies.add(520);
        frequencies.add(580);
        frequencies.add(480);

        frequencies.add(510);
        frequencies.add(380);
        frequencies.add(320);
        frequencies.add(440);
        frequencies.add(480);
        frequencies.add(450);
        frequencies.add(430);
        frequencies.add(380);
        frequencies.add(660);
        frequencies.add(760);
        frequencies.add(860);
        frequencies.add(700);
        frequencies.add(760);
        frequencies.add(660);
        frequencies.add(520);
        frequencies.add(580);
        frequencies.add(480);

        frequencies.add(500);

        frequencies.add(760);
        frequencies.add(720);
        frequencies.add(680);
        frequencies.add(620);

        frequencies.add(650);
        frequencies.add(380);
        frequencies.add(430);

        frequencies.add(500);
        frequencies.add(430);
        frequencies.add(500);
        frequencies.add(570);

        frequencies.add(500);

        frequencies.add(760);
        frequencies.add(720);
        frequencies.add(680);
        frequencies.add(620);

        frequencies.add(650);

        frequencies.add(1020);
        frequencies.add(1020);
        frequencies.add(1020);

        frequencies.add(380);
        frequencies.add(500);

        frequencies.add(760);
        frequencies.add(720);
        frequencies.add(680);
        frequencies.add(620);

        frequencies.add(650);
        frequencies.add(380);
        frequencies.add(430);

        frequencies.add(500);
        frequencies.add(430);
        frequencies.add(500);
        frequencies.add(570);

        frequencies.add(585);

        frequencies.add(550);

        frequencies.add(500);









        List<Integer> lengths = new ArrayList<Integer>();
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);

        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(80);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(80);
        lengths.add(50);
        lengths.add(100);
        lengths.add(80);
        lengths.add(50);
        lengths.add(80);
        lengths.add(80);
        lengths.add(80);
        lengths.add(80);

        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(80);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(80);
        lengths.add(50);
        lengths.add(100);
        lengths.add(80);
        lengths.add(50);
        lengths.add(80);
        lengths.add(80);
        lengths.add(80);
        lengths.add(80);

        lengths.add(100);

        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(150);

        lengths.add(150);
        lengths.add(100);
        lengths.add(100);

        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);

        lengths.add(100);

        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(150);

        lengths.add(200);

        lengths.add(80);
        lengths.add(80);
        lengths.add(80);

        lengths.add(100);
        lengths.add(100);

        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(150);

        lengths.add(150);
        lengths.add(100);
        lengths.add(100);

        lengths.add(100);
        lengths.add(100);
        lengths.add(100);
        lengths.add(100);

        lengths.add(100);

        lengths.add(100);

        lengths.add(100);









        List<Integer> delays = new ArrayList<Integer>();
        delays.add(150);
        delays.add(300);
        delays.add(300);
        delays.add(100);
        delays.add(300);
        delays.add(550);
        delays.add(575);

        delays.add(450);
        delays.add(400);
        delays.add(500);
        delays.add(300);
        delays.add(330);
        delays.add(150);
        delays.add(300);
        delays.add(200);
        delays.add(200);
        delays.add(150);
        delays.add(300);
        delays.add(150);
        delays.add(350);
        delays.add(300);
        delays.add(150);
        delays.add(150);
        delays.add(500);

        delays.add(450);
        delays.add(400);
        delays.add(500);
        delays.add(300);
        delays.add(330);
        delays.add(150);
        delays.add(300);
        delays.add(200);
        delays.add(200);
        delays.add(150);
        delays.add(300);
        delays.add(150);
        delays.add(350);
        delays.add(300);
        delays.add(150);
        delays.add(150);
        delays.add(500);

        delays.add(300);

        delays.add(100);
        delays.add(150);
        delays.add(150);
        delays.add(300);

        delays.add(300);
        delays.add(150);
        delays.add(150);

        delays.add(200);
        delays.add(150);
        delays.add(100);
        delays.add(220);

        delays.add(300);

        delays.add(100);
        delays.add(150);
        delays.add(150);
        delays.add(300);

        delays.add(300);

        delays.add(300);
        delays.add(150);
        delays.add(300);

        delays.add(300);
        delays.add(300);

        delays.add(100);
        delays.add(150);
        delays.add(150);
        delays.add(300);

        delays.add(300);
        delays.add(150);
        delays.add(150);

        delays.add(300);
        delays.add(150);
        delays.add(100);
        delays.add(420);

        delays.add(450);

        delays.add(420);

        delays.add(360 );


        for(int i = 0; i< frequencies.size();i++) {
            if(mAppMode != AppMode.MARIO) {
                break;
            }
            try {
                mSpeaker.play(frequencies.get(i));
                Thread.currentThread().sleep(lengths.get(i));
                mSpeaker.stop();
                Thread.currentThread().sleep(delays.get(i));
            } catch (Exception e) {
                Log.e(TAG,"Error playing song");
            }
        }
        try {
            mSpeaker.stop();
        } catch (IOException e) {

        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        AppMode mode = AppMode.HOME;
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                mode = AppMode.HOME;
                try {
                    aButtonLed.setValue(true);
                } catch (IOException e) {
                    Log.e(TAG, "error updating LED", e);
                }
                break;
            case KeyEvent.KEYCODE_B:
                mode = AppMode.KNIGHTRIGER;
                try {
                    bButtonLed.setValue(true);
                } catch (IOException e) {
                    Log.e(TAG, "error updating LED", e);
                }
                break;
            case KeyEvent.KEYCODE_C:
                mode = AppMode.MARIO;
                try {
                    cButtonLed.setValue(true);
                } catch (IOException e) {
                    Log.e(TAG, "error updating LED", e);
                }
                break;
            default:
                return super.onKeyDown(keyCode, event);
        }
        updateAppMode(mode);
        return true;
    }


    private void updateAppMode(AppMode newMode) {
        if (newMode != null && newMode == mAppMode) {
            return;
        }
        mAppMode = newMode;
        switch (newMode) {
            case HOME:
                startHomeMode();
                break;
            case KNIGHTRIGER:
                startKnightRiderMode();
                break;
            case MARIO:
                startMarioMode();
        }
    }

    private void startKnightRiderMode() {
        Log.d(TAG,"Start Knight Rider Mode");
        Runnable ledRunnable = new Runnable() {
            @Override
            public void run() {
                knightRiderLeds();
            }
        };
        Runnable displayRunnable = new Runnable() {
            @Override
            public void run() {
                while (mAppMode == AppMode.KNIGHTRIGER) {
                    floatTextOnDisplay("KNIGHT RIDER MODUS",AppMode.KNIGHTRIGER);
                }
            }
        };

        ledThread = new Thread(ledRunnable);
        ledThread.start();

        displayThread = new Thread(displayRunnable);
        displayThread.start();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                try {
                    aButtonLed.setValue(false);
                } catch (IOException e) {
                    Log.e(TAG, "error updating LED", e);
                }
                break;
            case KeyEvent.KEYCODE_B:
                try {
                    bButtonLed.setValue(false);
                } catch (IOException e) {
                    Log.e(TAG, "error updating LED", e);
                }
                break;
            case KeyEvent.KEYCODE_C:
                try {
                    cButtonLed.setValue(false);
                } catch (IOException e) {
                    Log.e(TAG, "error updating LED", e);
                }
                break;
            default:
                return super.onKeyUp(keyCode, event);
        }
        return true;
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up sensor registrations
        mSensorManager.unregisterListener(mTemperatureListener);
        mSensorManager.unregisterListener(mPressureListener);
        mSensorManager.unregisterDynamicSensorCallback(mDynamicSensorCallback);

        // Clean up peripheral.
        if (mEnvironmentalSensorDriver != null) {
            try {
                mEnvironmentalSensorDriver.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mEnvironmentalSensorDriver = null;
        }
        if (mButtonAInputDriver != null) {
            try {
                mButtonAInputDriver.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mButtonAInputDriver = null;
        }

        if (mButtonBInputDriver != null) {
            try {
                mButtonBInputDriver.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mButtonBInputDriver = null;
        }

        if (mDisplay != null) {
            try {
                mDisplay.clear();
                mDisplay.setEnabled(false);
                mDisplay.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling display", e);
            } finally {
                mDisplay = null;
            }
        }

        if (mLedstrip != null) {
            try {
                mLedstrip.write(new int[7]);
                mLedstrip.setBrightness(0);
                mLedstrip.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling ledstrip", e);
            } finally {
                mLedstrip = null;
            }
        }

        if (aButtonLed != null) {
            try {
                aButtonLed.setValue(false);
                aButtonLed.close();
            } catch (IOException e) {
                Log.e(TAG, "Error disabling led", e);
            } finally {
                aButtonLed = null;
            }
        }

        // clean up Cloud PubSub publisher.
        if (mPubsubPublisher != null) {
            mSensorManager.unregisterListener(mPubsubPublisher.getTemperatureListener());
            mSensorManager.unregisterListener(mPubsubPublisher.getPressureListener());
            mPubsubPublisher.close();
            mPubsubPublisher = null;
        }
    }

    private void updateDisplay(float value) {
        if (mDisplay != null) {
            try {
                mDisplay.display(value);
            } catch (IOException e) {
                Log.e(TAG, "Error setting display", e);
            }
        }
    }

    private void updateDisplay(String value) {
        if (mDisplay != null) {
            try {
                mDisplay.display(value);
            } catch (IOException e) {
                Log.e(TAG, "Error setting display", e);
            }
        }
    }

    private void floatTextOnDisplay(String text,AppMode mode) {
        clearDisplay();
        List<String> displayedCharacters = new ArrayList<String>();
        int displayLength = 4;
        int numberOfSteps = text.length() + displayLength;
            for(int i = 0; i <numberOfSteps; i++) {
                if(mAppMode != mode) {
                    break;
                }
                displayedCharacters.clear();
                int numberOfEmptySpace = displayLength - i;
                for (int j = 0; j < numberOfEmptySpace; j++) {
                    displayedCharacters.add(" ");
                }
                int leftDisplaySpace = displayLength - displayedCharacters.size();
                int startIndex = 0;
                if (numberOfEmptySpace < 0) {
                    startIndex = i - displayLength;
                }
                for (int k = 0; k < leftDisplaySpace; k++) {
                    int characterIndex = startIndex + k;
                    if (characterIndex >= 0 && characterIndex < text.length()) {
                        String character = String.valueOf(text.charAt(characterIndex));
                        displayedCharacters.add(character);
                    } else {
                        displayedCharacters.add(" ");
                    }
                }
                displayString(displayedCharacters);
                try {
                    Thread.currentThread().sleep(500);
                } catch (InterruptedException e) {
                    Log.e(TAG, "InterruptedException");
                }
            }
    }

    private void displayString(List<String> strings) {
        String result = "";
        for(String character: strings){
            result += character;
        }
        updateDisplay(result);
    }
    private void clearDisplay() {
        if (mDisplay != null) {
            try {
                mDisplay.clear();
            } catch (IOException e) {
                Log.e(TAG, "Error clearing display", e);
            }
        }
    }

    private void updateBarometer(float pressure) {
        // Update UI.
        if (pressure > BAROMETER_RANGE_SUNNY) {
            mImageView.setImageResource(R.drawable.ic_sunny);
        } else if (pressure < BAROMETER_RANGE_RAINY) {
            mImageView.setImageResource(R.drawable.ic_rainy);
        } else {
            mImageView.setImageResource(R.drawable.ic_cloudy);
        }
        // Update led strip.
        if (mLedstrip == null) {
            return;
        }
        float t = (pressure - BAROMETER_RANGE_LOW) / (BAROMETER_RANGE_HIGH - BAROMETER_RANGE_LOW);
        int n = (int) Math.ceil(mRainbow.length * t);
        n = Math.max(0, Math.min(n, mRainbow.length));
        int[] colors = new int[mRainbow.length];
        for (int i = 0; i < n; i++) {
            int ri = mRainbow.length - 1 - i;
            colors[ri] = mRainbow[ri];
        }
        try {
            mLedstrip.write(colors);
        } catch (IOException e) {
            Log.e(TAG, "Error setting ledstrip", e);
        }
    }
}

