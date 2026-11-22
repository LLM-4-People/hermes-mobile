package org.llm4people.hermes.journey;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestRunner;
import android.view.View;
import org.llm4people.hermes.MainActivity;

public final class PolishCaptureRunner extends InstrumentationTestRunner {
    private boolean landscape;
    private volatile Activity resumed;
    @Override public void onCreate(Bundle args) {
        String mode=args.getString("mode");
        landscape="paysage".equals(mode) || "large".equals(mode);
        super.onCreate(args);
    }
    @Override public void callActivityOnCreate(Activity activity,Bundle state) {
        activity.setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        super.callActivityOnCreate(activity,state);
    }
    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        resumed=activity;
    }
    MainActivity ready(int width,int height) {
        MainActivity[] found=new MainActivity[1];
        long end=SystemClock.elapsedRealtime()+30000;
        do {
            runOnMainSync(()->{
                if(resumed instanceof MainActivity app && !app.isDestroyed() && !app.isFinishing()){
                    View root=app.getWindow().getDecorView();
                    if(root.hasWindowFocus() && root.getWidth()==width && root.getHeight()==height)found[0]=app;
                }
            });
            if(found[0]!=null)return found[0];
            SystemClock.sleep(70);
        }while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError("Native Activity did not settle at requested "+width+"×"+height);
    }
}
