package io.github.abyts1.ember;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Model m = Model.load(c);
        if (m.enabled && m.hasLocation() && Settings.canDrawOverlays(c)) OverlayService.start(c);
    }
}
