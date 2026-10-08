package com.mobisys.cordova.plugins.mlkit.barcode.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.common.api.CommonStatusCodes;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * This class echoes a string called from JavaScript.
 */
// ThaiEMS fork (hooks/patches/mlkit-barcode-scanner-android): ผลจากปุ่มถ่ายรูป + กัน crash ตอนกด back
public class MLKitBarcodeScanner extends CordovaPlugin {

  private static final int RC_BARCODE_CAPTURE = 9001;
  private CallbackContext _CallbackContext;
  private Boolean _BeepOnSuccess;
  private Boolean _VibrateOnSuccess;
  private MediaPlayer _MediaPlayer;
  private Vibrator _Vibrator;

  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);

    Context context = cordova.getContext();

    _Vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    _MediaPlayer = new MediaPlayer();

    try {
      AssetFileDescriptor descriptor = context.getAssets().openFd("beep.ogg");
      _MediaPlayer.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
      descriptor.close();
      _MediaPlayer.prepare();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    Activity activity = cordova.getActivity();
    Boolean hasCamera = activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

    _CallbackContext = callbackContext;

    int numberOfCameras = 0;

    try {
      numberOfCameras = cameraManager.getCameraIdList().length;
    } catch (Exception e) {
      e.printStackTrace();
    }

    if (!hasCamera || numberOfCameras == 0) {
      AlertDialog alertDialog = new AlertDialog.Builder(activity).create();
      alertDialog.setMessage(activity.getString(activity.getResources()
          .getIdentifier("no_cameras_found", "string", activity.getPackageName())));
      alertDialog.setButton(
          AlertDialog.BUTTON_POSITIVE, activity.getString(activity.getResources()
              .getIdentifier("ok", "string", activity.getPackageName())),
          new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
              dialog.dismiss();
            }
          });
      alertDialog.show();
      return false;
    }

    if (action.equals("startScan")) {
      class OneShotTask implements Runnable {
        private final Context context;
        private final JSONArray args;

        private OneShotTask(Context ctx, JSONArray as) {
          context = ctx;
          args = as;
        }

        public void run() {
          try {
            openNewActivity(context, args);
          } catch (JSONException e) {
            _CallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.toString()));
          }
        }
      }
      Thread t = new Thread(new OneShotTask(cordova.getContext(), args));
      t.start();
      return true;
    }
    return false;
  }

  private void openNewActivity(Context context, JSONArray args) throws JSONException {
    JSONObject config = args.getJSONObject(0);
    Intent intent = new Intent(context, CaptureActivity.class);
    intent.putExtra("BarcodeFormats", config.optInt("barcodeFormats", 1234));
    intent.putExtra("DetectorSize", config.optDouble("detectorSize", 0.5));
    intent.putExtra("RotateCamera", config.optBoolean("rotateCamera", false));

    _BeepOnSuccess = config.optBoolean("beepOnSuccess", false);
    _VibrateOnSuccess = config.optBoolean("vibrateOnSuccess", false);

    this.cordova.setActivityResultCallback(this);
    this.cordova.startActivityForResult(this, intent, RC_BARCODE_CAPTURE);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode == RC_BARCODE_CAPTURE) {
      if (resultCode == CommonStatusCodes.SUCCESS && data != null && data.getStringExtra(CaptureActivity.PhotoPath) != null) {
        // ThaiEMS: ปุ่มถ่ายรูปในหน้าสแกน → ["", 0, 0, dataUrl] เหมือน sendPhotoResult ของ fork iOS
        final String path = data.getStringExtra(CaptureActivity.PhotoPath);
        final CallbackContext cb = _CallbackContext;
        cordova.getThreadPool().execute(new Runnable() {
          @Override
          public void run() {
            File file = new File(path);
            try {
              byte[] bytes = new byte[(int) file.length()];
              FileInputStream in = new FileInputStream(file);
              try {
                int off = 0;
                while (off < bytes.length) {
                  int n = in.read(bytes, off, bytes.length - off);
                  if (n < 0) break;
                  off += n;
                }
              } finally {
                in.close();
              }
              JSONArray result = new JSONArray();
              result.put("");
              result.put(0);
              result.put(0);
              result.put("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP));
              cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, result));
            } catch (IOException e) {
              JSONArray result = new JSONArray();
              result.put("PHOTO_FAILED");
              result.put("");
              result.put("");
              cb.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, result));
            } finally {
              file.delete();
            }
          }
        });
      } else if (resultCode == CommonStatusCodes.SUCCESS) {
        if (data != null) {
          Integer barcodeFormat = data.getIntExtra(CaptureActivity.BarcodeFormat, 0);
          Integer barcodeType = data.getIntExtra(CaptureActivity.BarcodeType, 0);
          String barcodeValue = data.getStringExtra(CaptureActivity.BarcodeValue);
          JSONArray result = new JSONArray();
          result.put(barcodeValue);
          result.put(barcodeFormat);
          result.put(barcodeType);
          _CallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, result));

          if (_BeepOnSuccess) {
            _MediaPlayer.start();
          }

          if (_VibrateOnSuccess) {
            Integer duration = 200;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              _Vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
              // deprecated in API 26 aka Oreo
              _Vibrator.vibrate(duration);
            }
          }

          Log.d("MLKitBarcodeScanner", "Barcode read: " + barcodeValue);
        }
      } else {
        // ThaiEMS: ปุ่ม back ของระบบเดิมคืน data = null → NullPointerException (แอป crash)
        String err = data != null ? data.getStringExtra("err") : null;
        if (err == null) err = "USER_CANCELLED";
        JSONArray result = new JSONArray();
        result.put(err);
        result.put("");
        result.put("");
        _CallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, result));
      }
    }
  }

  @Override
  public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
    _CallbackContext = callbackContext;
  }
}
