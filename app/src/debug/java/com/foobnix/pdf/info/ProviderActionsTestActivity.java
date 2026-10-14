package com.foobnix.pdf.info;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Instrumentation host for real dialogs with an in-process provider and captured handoffs. */
public class ProviderActionsTestActivity extends Activity {
    public static ContentResolver resolver;
    public final BlockingQueue<Intent> handoffs = new LinkedBlockingQueue<>();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new android.widget.FrameLayout(this));
    }
    @Override public ContentResolver getContentResolver() {
        return resolver == null ? super.getContentResolver() : resolver;
    }
    @Override public void startActivity(Intent intent) { handoffs.add(intent); }
}
