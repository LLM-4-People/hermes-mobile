package org.llm4people.hermes.journey;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import org.json.JSONObject;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.AdverseAccountFixture;
import org.llm4people.hermes.R;
import org.llm4people.hermes.thread.PolishPromptFixture;

public final class PolishAdverseTest extends InstrumentationTestCase {
    MainActivity activity;
    PolishPromptFixture decisions;
    JourneyUi ui;
    String mode, server;
    File dir;
    JSONObject receipt=new JSONObject();
    private void main(Callable<?> task) throws Throwable {
        runTestOnUiThread(() -> {try {task.call();} catch(Exception e){throw new AssertionError(e);}});
    }
    private static View named(View view,String name) {
        if (!view.isShown()) return null;
        if (view instanceof TextView text && name.contentEquals(text.getText())) return view;
        if (name.equals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup g) for (int i=0;i<g.getChildCount();i++) {View v=named(g.getChildAt(i),name);if(v!=null)return v;}
        return null;
    }
    private void shot(String suffix) {ui.capture(mode+"-"+suffix);}
    private void bounds(String label) throws Throwable {
        ui.see(label);
        main(()->{
            for (View root:WindowInspector.getGlobalWindowViews()) if(root.hasWindowFocus()){
                View v=named(root,label); if(v==null) continue;
                Rect r=new Rect(); assertTrue(label,v.getGlobalVisibleRect(r));
                float scale=activity.getResources().getDisplayMetrics().density;
                assertTrue(label+" height "+r.height(),r.height()>=48*scale && r.height()==v.getHeight());
                if(v instanceof TextView t && t.getLayout()!=null) for(int i=0;i<t.getLineCount();i++)
                    assertEquals(label,0,t.getLayout().getEllipsisCount(i));
                receipt.put(label,r.flattenToString());return null;
            }
            throw new AssertionError(label);
        });
    }
    private void stableImeRow(String label,boolean require) throws Throwable {
        int[] previous={-1,-1,-1},current={-1,-1,-1};int stable=0;
        long end=android.os.SystemClock.elapsedRealtime()+8500;
        do {
            java.net.HttpURLConnection http=(java.net.HttpURLConnection)new java.net.URL(server+"/__proof/ime").openConnection();
            http.setReadTimeout(5000);
            String line;
            try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(http.getInputStream()))){line=reader.readLine();}
            finally{http.disconnect();}
            JSONObject wm=new JSONObject(line);int top=wm.getInt("top"),bottom=wm.getInt("bottom");
            main(()->{
                current[0]=0;current[1]=bottom-top;current[2]=top;
                for(View root:WindowInspector.getGlobalWindowViews())if(root.hasWindowFocus()){
                    View row=named(root,label);Rect visible=new Rect();int[] origin=new int[2];root.getLocationOnScreen(origin);
                    if(row!=null && row.getGlobalVisibleRect(visible)){
                        visible.offset(origin[0],origin[1]);
                        if(visible.intersect(0,0,2000,top))current[0]=visible.height();
                    }
                    receipt.put("focusedWindow",root.getWidth()+"×"+root.getHeight());
                }
                return null;
            });
            stable=java.util.Arrays.equals(previous,current)&&current[1]>0?stable+1:0;
            System.arraycopy(current,0,previous,0,3);
            if(stable<2)android.os.SystemClock.sleep(90);
        }while(stable<2 && android.os.SystemClock.elapsedRealtime()<end);
        receipt.put(label+" settled",stable>=2).put(label+" above keyboard",current[0]).put("modelPickerImeBottom",current[1]).put("modelPickerUsableBottom",current[2]);
        Files.write(new File(dir,mode+".json").toPath(),receipt.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(stable<2)shot("mesure-clavier");
        assertTrue("System IME frame must be stable "+receipt,stable>=2);
        if(require)assertTrue("48dp reachable for "+label+" "+receipt,current[0]>=48);
    }
    public void testRealNativeChrome() throws Throwable {
        Context ctx=getInstrumentation().getTargetContext(); assertEquals("org.llm4people.polishprobe",ctx.getPackageName());
        Bundle args=((InstrumentationTestRunner)getInstrumentation()).getArguments();
        mode=args.getString("mode");server=args.getString("server");dir=new File(ctx.getCacheDir(),"captures");dir.mkdirs();
        activity=(MainActivity)getInstrumentation().startActivitySync(ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName()));
        activity=((PolishCaptureRunner)getInstrumentation()).ready(mode.equals("large")?1200:mode.equals("paysage")?640:320,mode.equals("large")?720:mode.equals("paysage")?320:640);
        ui=new JourneyUi(getInstrumentation(),activity,dir);
        try(AdverseAccountFixture fixture=new AdverseAccountFixture(ctx,args.getString("server"))) {
            ui.see("Connecter Hermes");
            main(()->{Method m=MainActivity.class.getDeclaredMethod("opened",Account.class);m.setAccessible(true);m.invoke(activity,fixture.account);return null;});
            ui.see("Nouvelle conversation");ui.tap("Nouvelle conversation");ui.see("Message à Hermes");
            main(()->{
                View root=activity.getWindow().getDecorView();Configuration c=activity.getResources().getConfiguration();
                boolean night=(c.uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
                assertEquals(mode.equals("nuit") || mode.equals("paysage"),night);
                assertEquals((mode.equals("nuit")||mode.equals("paysage"))?1.5f:1.0f,c.fontScale,0.02f);
                assertEquals(mode.equals("large")?1200:mode.equals("paysage")?640:320,root.getWidth());
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
                receipt.put("width",root.getWidth()).put("height",root.getHeight()).put("fontScale",c.fontScale).put("night",night);
                return null;
            });
            if (!mode.equals("paysage")) {
            ui.see("Comprendre du code");ui.see("Planifier mon travail");ui.see("Poser une question");ui.usableComposer();shot("premier-message");
            ui.tap("Message à Hermes");ui.fill("Message à Hermes","Relire les arrondis dans remise.py");
            ui.imeVisible();ui.usableComposer();
            main(()->{View v=activity.getWindow().getDecorView();receipt.put("imeBottom",v.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom);return null;});
            shot("brouillon-clavier");ui.hideKeyboard();
            }
            ui.tap("Autres actions");ui.tap("Modèle et outils");
            ui.see("Utiliser les outils du profil");shot("reglages");
            ui.tap("Choisir le modèle");ui.see("Atelier · atelier");shot("fournisseurs");
            ui.tap("Atelier · atelier");ui.see("Relecture locale");shot("modeles");
            ui.tap("Rechercher");ui.imeVisible();ui.fill("Rechercher","Relec");ui.see("Relecture locale");
            stableImeRow("Relecture locale",!mode.equals("paysage"));shot("modeles-clavier");
            if(receipt.getInt("Relecture locale above keyboard")<48)ui.back();
            ui.tap("Relecture locale");ui.see("atelier · Relecture locale");
            shot("modele-choisi");
            if (mode.equals("nuit") || mode.equals("paysage")) {
                ui.tap("Choisir le modèle");
                String provider="Atelier de développement et d’assistance pour les équipes de recherche et de documentation · custom-local-research-team-openai-compatible-staging";
                ui.tap(provider);ui.see("Analyse locale");ui.tap("Rechercher");
                ui.imeVisible();ui.fill("Rechercher","Analyse");getInstrumentation().waitForIdleSync();
                stableImeRow("Analyse locale",false);
                shot("modele-nom-long-clavier");
                boolean tapWithIme=receipt.getInt("Analyse locale above keyboard")>=48;
                if(!tapWithIme)ui.back();
                main(()->{fixture.rejectNextSave();return null;});ui.tap("Analyse locale");
                ui.see(activity.getString(R.string.panel_storage_error));ui.see("Analyse locale");
                receipt.put("rejectedAttemptWithIme",tapWithIme);
                if(tapWithIme)stableImeRow("Analyse locale",true);
                shot("modele-erreur-nom-long");
                ui.tap("Analyse locale");ui.see("custom-local-research-team-openai-compatible-staging · Analyse locale");
                receipt.put("retriedSelection",true);
            }
            if(mode.equals("paysage")){
                Files.write(new File(dir,mode+".json").toPath(),receipt.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            ui.back();ui.see("Message à Hermes");
            main(()->{decisions=new PolishPromptFixture(activity);decisions.show("question");return null;});
            ui.showPendingControl("Les arrondis");ui.see("Les cas limites");shot("question");
            main(()->{decisions.show("free");return null;});
            ui.showPendingControl("Quel résultat souhaitez-vous obtenir en priorité ?");
            ui.fill("Votre réponse","Vérifier les arrondis.");ui.see("Envoyer la réponse");shot("reponse-libre");
            main(()->{decisions.show("approval");return null;});
            ui.showPendingControl("Autoriser une fois");ui.see("Refuser");ui.revealText("Refuser");
            bounds("Autoriser une fois");bounds("Refuser");shot("autorisation");
            ui.tapInListRow("Refuser","Voir les détails");ui.containsInListRow("Refuser","chmod 666 partage-demo.txt");
            shot("autorisation-details");
            Files.write(new File(dir,mode+".json").toPath(),receipt.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {runTestOnUiThread(activity::finish);}
    }
}
