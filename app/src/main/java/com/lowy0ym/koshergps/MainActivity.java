package com.lowy0ym.koshergps;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private LocationManager lm;
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private static final int LOC = 42;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUserAgentString("KosherGPS/1.0");
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        requestLocation();
    }

    private void requestLocation() {
        lm = (LocationManager)getSystemService(LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOC);
            return;
        }
        startLocation();
    }

    private void startLocation() {
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 3, listener);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10, listener);
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) pushLocation(last);
        } catch (SecurityException ignored) {}
    }

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { pushLocation(l); }
    };

    private void pushLocation(Location l) {
        String js = "window.onNativeLocation && window.onNativeLocation(" + l.getLatitude() + "," + l.getLongitude() + "," + l.getAccuracy() + ");";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == LOC) startLocation();
    }

    private static String enc(String x) {
        try { return URLEncoder.encode(x, StandardCharsets.UTF_8.name()); }
        catch(Exception e) { return ""; }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent","KosherGPS/1.0");
        c.setRequestProperty("Accept","application/json");
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private void send(String function, String data) {
        String js = "window." + function + "(" + data + ");";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    public class Bridge {
        @JavascriptInterface public void search(String query) {
            net.execute(() -> {
                try {
                    String u="https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&addressdetails=1&q="+enc(query);
                    send("onSearch", get(u));
                } catch(Exception e){ send("onError","\"Search failed\""); }
            });
        }

        @JavascriptInterface public void route(double aLat,double aLon,double bLat,double bLon) {
            net.execute(() -> {
                try {
                    String u="https://router.project-osrm.org/route/v1/driving/"+aLon+","+aLat+";"+bLon+","+bLat+"?overview=full&geometries=geojson&steps=true&alternatives=true";
                    send("onRoute", get(u));
                } catch(Exception e){ send("onError","\"Route failed\""); }
            });
        }

        @JavascriptInterface public void photos(String query) {
            net.execute(() -> {
                try {
                    String u="https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch="+enc(query)+"&gsrnamespace=6&gsrlimit=8&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=900&format=json&origin=*";
                    send("onPhotos", get(u));
                } catch(Exception e){ send("onError","\"Photo search failed\""); }
            });
        }

        @JavascriptInterface public void openChatGPT(String prompt) {
            try {
                Intent i=new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/?q="+enc(prompt)));
                startActivity(i);
            } catch(Exception ignored) {}
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if(lm!=null) lm.removeUpdates(listener);
        net.shutdownNow();
    }
}
