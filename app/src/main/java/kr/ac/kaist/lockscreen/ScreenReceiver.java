package kr.ac.kaist.lockscreen;

//화면이 켜졌을 때 ACTION_SCREEN_OFF intent 를 받는다.

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import java.net.MalformedURLException;
import java.util.Calendar;

import static android.content.Context.POWER_SERVICE;


public class ScreenReceiver extends BroadcastReceiver {

    public static final String TAG = "ScreenReceiver";
    private DatabaseHelper db;
    protected SharedPreferences sharedPref = null;
    protected SharedPreferences.Editor sharedPrefEditor = null;
    private Context context;
    private static final int screen_appear_threshold = 1;

    @Override
    public void onReceive(Context con, Intent intent) {
        context = con;

        db = new DatabaseHelper(context);

        sharedPref = context.getSharedPreferences("Modes", Activity.MODE_PRIVATE);
        sharedPrefEditor = sharedPref.edit();


        int focus = sharedPref.getInt("FocusMode", -1);

        Calendar calStart = Calendar.getInstance();
        Calendar calEnd = Calendar.getInstance();
        long start_time = sharedPref.getLong("data_start_timestamp", -1);
        long end_time = System.currentTimeMillis();
        long duration = (end_time - start_time) / 1000;

        //pref_other = context.getSharedPreferences("OtherApp", Activity.MODE_PRIVATE); //다른 앱(홈화면 포함) 실행 중인가?
        //editor_other = pref_other.edit();

        if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
            sharedPrefEditor.putInt("Typing", 1);
            sharedPrefEditor.apply();

            if (focus == 1 && duration > screen_appear_threshold) {
                Log.d(TAG, "The smartphone screen is on (timer expired)");
                Intent i = new Intent(context, LockScreen.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            }
        }

        if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
            //SharedPreferences pref_other = context.getSharedPreferences("OtherApp",Context.MODE_PRIVATE);
            int flag = sharedPref.getInt("Flag", -1);
            //int otherApp = pref_other.getInt("OtherApp",-1);

            sharedPrefEditor.putInt("Typing", 0);
            sharedPrefEditor.apply();

            Log.d(TAG, "Smartphone screen is OFF: " + String.valueOf(flag));

            if (flag != 1 || focus == 0) { // 만약에 잠금 화면에서 화면이 꺼진 것이라면 reset하지 않는다. 그리고 timer가 trigger되지 않았으면.
                final Intent intentService = new Intent(context, CountService.class);
                sharedPrefEditor.putInt("Flag", 0);
                sharedPrefEditor.apply();
                context.stopService(intentService);
                context.startService(intentService);
            }

            /*
            if(otherApp == 1 && focus == 0){
                final Intent intentService = new Intent(context, CountService.class);
                editor_flag.putInt("Flag",0);
                editor_flag.commit();
                context.stopService(intentService);
                context.startService(intentService);
            }
            editor_other.putInt("OtherApp",0);
            editor_other.commit();
            */
        }

        if (intent.getAction().equals("kr.ac.kaist.lockscreen.shake")) {
            Log.d(TAG, "Shake (movement detected)");
            PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);

            boolean isInteractive = false;
            if (powerManager != null) {
                isInteractive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH && powerManager.isInteractive() || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH && powerManager.isScreenOn();
            }

            //State Type 1 -> movement
            if (focus == 1 && !isInteractive) {
                calStart.setTimeInMillis(start_time);
                calEnd.setTimeInMillis(end_time);

                db = new DatabaseHelper(context); //reinit DB
                submitRawData(calStart.getTimeInMillis(), calEnd.getTimeInMillis(), (int) duration, (short) 1, "", "", "");
            }


        }

        if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            Intent i = new Intent(context, CountService.class);
            context.startService(i);
        }
    }

    public void submitRawData(long start_time, long end_time, int duration, short type, String location_txt, String activity_txt, String otherESMResponse) {
        if (Tools.isNetworkAvailable(context)) {
            Tools.executeForService(new MyServiceRunnable(
                    context.getString(R.string.url_server, context.getString(R.string.server_ip)),
                    SignInActivity.loginPrefs.getString(SignInActivity.email, null),
                    start_time,
                    end_time,
                    duration,
                    type,
                    location_txt,
                    activity_txt,
                    otherESMResponse
            ) {
                @Override
                public void run() {
                    String url = (String) args[0];
                    String email = (String) args[1];
                    long start_time = (long) args[2];
                    long end_time = (long) args[3];
                    int duration = (int) args[4];
                    short type = (short) args[5];
                    String location_txt = (String) args[6];
                    String activity_txt = (String) args[7];
                    String otherESMResp = (String) args[8];

                    PHPRequest request;
                    try {
                        request = new PHPRequest(url);
                        String result = request.PhPtest(PHPRequest.SERV_CODE_ADD_RD, email, String.valueOf(type), location_txt, "", activity_txt, "", String.valueOf(start_time), String.valueOf(end_time), String.valueOf(duration), String.valueOf(otherESMResp)); //TODO: remove empty strings for icons
                        if (result == null) {
                            boolean isInserted = db.insertRawData(start_time, end_time, duration, type, location_txt, activity_txt, otherESMResp);

                            if (isInserted) {
                                Log.d(TAG, "State saved to local");
                            } else
                                Log.d(TAG, "Failed to save to local");

                            restartServiceAndGoHome();
                        } else {
                            switch (result) {
                                case Tools.RES_OK:
                                    Log.d(TAG, "Submitted");
                                    restartServiceAndGoHome();
                                    try {
                                        Thread.sleep(500);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    break;
                                case Tools.RES_FAIL:
                                    Log.d(TAG, "Failed to submit");
                                    break;
                                case Tools.RES_SRV_ERR:
                                    Log.d(TAG, "Failed to sign up. (SERVER SIDE ERROR)");
                                    break;
                                default:
                                    break;
                            }
                        }

                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }

                }
            });
        } else {
            boolean isInserted = db.insertRawData(start_time, end_time, duration, type, location_txt, activity_txt, otherESMResponse);

            if (isInserted) {
                Toast.makeText(context, "State saved", Toast.LENGTH_SHORT).show();
            } else
                Toast.makeText(context, "Failed to save", Toast.LENGTH_SHORT).show();

            restartServiceAndGoHome();

        }
        sharedPrefEditor.putInt("Flag", 0);
        sharedPrefEditor.apply();
    }

    public void restartServiceAndGoHome() {

        sharedPrefEditor.putInt("FocusMode", 0);
        sharedPrefEditor.apply();

        final Intent intentService = new Intent(context, CountService.class);
        context.stopService(intentService);
        context.startService(intentService);

        Intent intent_home = new Intent(Intent.ACTION_MAIN); //태스크의 첫 액티비티로 시작
        intent_home.addCategory(Intent.CATEGORY_HOME);   //홈화면 표시
        intent_home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); //새로운 태스크를 생성하여 그 태스크안에서 액티비티 추가
        context.startActivity(intent_home);

    }
}


