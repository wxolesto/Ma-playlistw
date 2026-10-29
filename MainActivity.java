package com.arsenous.base;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ArsenousAudioPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
