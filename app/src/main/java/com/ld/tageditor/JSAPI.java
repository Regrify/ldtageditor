package com.ld.tageditor;

import android.nfc.tech.MifareUltralight;
import android.nfc.tech.NfcA;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.io.IOException;

public class JSAPI {
    MainActivity activity;
    WebView web;
    private volatile String lastError = "";

    public JSAPI(MainActivity paramActivity, WebView paramWeb) {
        this.activity = paramActivity;
        this.web = paramWeb;
    }

    @JavascriptInterface
    public String getLastError() {
        return this.lastError;
    }

    private static int rotr32(int a, int b) {
        return (a >>> b) | (a << (32 - b));
    }

    /**
     * Ports the app.js `pwdgen()` algorithm (LEGO Dimensions tag password
     * derivation from the 7-byte NTAG21x UID). Official LEGO tags ship with
     * this password already programmed and require PWD_AUTH (0x1B) before
     * the data pages (0x24 and above) can be read.
     */
    private static byte[] computePassword(byte[] uid) {
        byte[] base;
        try {
            base = "UUUUUUU(c) Copyright LEGO 2014AA".getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            base = "UUUUUUU(c) Copyright LEGO 2014AA".getBytes();
        }
        int copyLen = Math.min(uid.length, 7);
        System.arraycopy(uid, 0, base, 0, copyLen);
        base[30] = (byte) 0xAA;
        base[31] = (byte) 0xAA;

        int v2 = 0;
        for (int i = 0; i < 8; i++) {
            int v4 = rotr32(v2, 25);
            int v5 = rotr32(v2, 10);
            int b = ((base[i * 4] & 0xFF))
                    | ((base[i * 4 + 1] & 0xFF) << 8)
                    | ((base[i * 4 + 2] & 0xFF) << 16)
                    | ((base[i * 4 + 3] & 0xFF) << 24);
            v2 = b + v4 + v5 - v2;
        }

        // pwdgen writes v2 big-endian then re-reads it little-endian, which
        // is equivalent to a byte-swap of the 32-bit word. The resulting
        // bytes are [LSB, ..., MSB] of the original v2.
        byte[] pwd = new byte[4];
        pwd[0] = (byte) (v2 & 0xFF);
        pwd[1] = (byte) ((v2 >>> 8) & 0xFF);
        pwd[2] = (byte) ((v2 >>> 16) & 0xFF);
        pwd[3] = (byte) ((v2 >>> 24) & 0xFF);
        return pwd;
    }

    private void authenticate(NfcA nfcA) {
        try {
            byte[] uid = this.activity.tag.getId();
            byte[] pwd = computePassword(uid);
            byte[] authCmd = new byte[] {
                    (byte) 0x1B,
                    pwd[0], pwd[1], pwd[2], pwd[3]
            };
            Log.i("JSAPI", "PWD_AUTH " + bytesToHex(pwd));
            byte[] pack = nfcA.transceive(authCmd);
            Log.i("JSAPI", "PWD_AUTH result " + (pack != null ? bytesToHex(pack) : "null"));
        } catch (IOException e) {
            // Tag may not be password-protected (e.g. a blank/custom tag) -
            // ignore and continue with the read/write attempt.
            Log.w("JSAPI", "PWD_AUTH failed/not required: " + e.getMessage());
        } catch (Exception e) {
            Log.w("JSAPI", "PWD_AUTH error: " + e.getMessage());
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /**
     * Reads multiple 4-page blocks in a single NfcA connection to avoid
     * TagLostException caused by the tag losing RF contact between separate
     * JS-bridge readTag() calls (each of which connects/disconnects).
     * pagesCsv is a comma-separated list of page numbers, e.g. "0,36".
     * Returns the concatenated 16-byte reads, base64-encoded, in the same
     * order as the requested pages.
     */
    @JavascriptInterface
    public String readTagMulti(String pagesCsv) {
        String[] parts = pagesCsv.split(",");
        NfcA nfcA = NfcA.get(this.activity.tag);
        try {
            Log.i("JSAPI", "Connecting (multi)");
            nfcA.connect();
            Log.i("JSAPI", "Connected (multi)");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            boolean needAuth = false;
            for (String part : parts) {
                int page = Integer.parseInt(part.trim());
                byte[] message = new byte[] {
                        0x30,
                        (byte) (page & 0xFF)
                };
                byte[] payload;
                try {
                    payload = nfcA.transceive(message);
                } catch (IOException readFail) {
                    Log.w("JSAPI", "Plain read of page " + page + " failed: " + readFail.getMessage());
                    needAuth = true;
                    break;
                }
                out.write(payload, 0, 16);
            }
            if (needAuth) {
                Log.i("JSAPI", "Reconnecting with PWD_AUTH for protected pages");
                try { nfcA.close(); } catch (IOException ignore) {}
                nfcA.connect();
                authenticate(nfcA);
                out.reset();
                for (String part : parts) {
                    int page = Integer.parseInt(part.trim());
                    byte[] message = new byte[] {
                            0x30,
                            (byte) (page & 0xFF)
                    };
                    byte[] payload = nfcA.transceive(message);
                    out.write(payload, 0, 16);
                }
            }
            String encodeToString = Base64.encodeToString(out.toByteArray(), 0, out.size(), 0);
            Log.i("JSAPI", encodeToString);
            return encodeToString;
        } catch (IOException e) {
            Log.e("JSAPI", "IOException while reading tag (multi)...", e);
            this.lastError = "readTagMulti(" + pagesCsv + ") " + e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        } finally {
            if (nfcA != null) {
                try {
                    nfcA.close();
                } catch (IOException e22) {
                    Log.e("JSAPI", "Error closing tag...", e22);
                }
            }
        }
    }

    /**
     * Writes multiple 4-byte pages in a single NfcA session.
     * @param pagesCsv comma-separated page numbers (e.g. "36,37,38,39")
     * @param dataCsv  comma-separated base64-encoded 4-byte payloads
     * @return true if all writes succeeded
     */
    @JavascriptInterface
    public boolean writeTagMulti(String pagesCsv, String dataCsv) {
        String[] pageParts = pagesCsv.split(",");
        String[] dataParts = dataCsv.split(",");
        if (pageParts.length != dataParts.length) {
            this.lastError = "writeTagMulti: page/data count mismatch";
            return false;
        }
        NfcA nfcA = NfcA.get(this.activity.tag);
        try {
            Log.i("JSAPI", "Connecting (multi-write)");
            nfcA.connect();
            Log.i("JSAPI", "Connected (multi-write)");

            // Always authenticate first to avoid a two-pass partial-write bug:
            // if the first (unauthenticated) pass writes some pages but fails on
            // a page that needs auth, those pages are already modified when the
            // second (authenticated) pass starts.  If the second pass then fails
            // on a locked page, the tag is left in a partially-written state.
            // Authenticating up front (safe on non-protected tags – the PWD_AUTH
            // command simply fails and is ignored) lets us write everything in
            // a single pass.
            authenticate(nfcA);
            for (int i = 0; i < pageParts.length; i++) {
                int page = Integer.parseInt(pageParts[i].trim());
                byte[] data = Base64.decode(dataParts[i].trim(), 0);
                byte[] message = new byte[] {
                        (byte) 0xA2,
                        (byte)(page & 0xFF),
                        data[0], data[1], data[2], data[3]
                };
                byte[] result = nfcA.transceive(message);
                checkWriteAck(result, page);
            }
            Log.i("JSAPI", "Multi-write done");
            return true;
        } catch (IOException e) {
            Log.e("JSAPI", "IOException while writing tag (multi)...", e);
            this.lastError = "writeTagMulti(" + pagesCsv + ") " + e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        } finally {
            if (nfcA != null) {
                try {
                    nfcA.close();
                } catch (IOException e22) {
                    Log.e("JSAPI", "Error closing tag (multi-write)...", e22);
                }
            }
        }
    }

    @JavascriptInterface
    public String readTag(byte page) {
//        MifareUltralight mifare = MifareUltralight.get(this.activity.tag);
        NfcA nfcA = NfcA.get(this.activity.tag);
        try {
            Log.i("JSAPI", "Connecting");
            nfcA.connect();
//            mifare.connect();
            Log.i("JSAPI", "Connected");
            Log.i("JSAPI", "Read");

            byte[] message = new byte[] {
                    0x30,
                    (byte)(page & 0xFF)
            };

//            byte[] payload = mifare.readPages(page);
            byte[] payload;
            try {
                payload = nfcA.transceive(message);
            } catch (IOException readFail) {
                Log.w("JSAPI", "Plain read of page " + page + " failed, retrying with PWD_AUTH: " + readFail.getMessage());
                try { nfcA.close(); } catch (IOException ignore) {}
                nfcA.connect();
                authenticate(nfcA);
                payload = nfcA.transceive(message);
            }
//            Log.i("JSAPI", String.format("Payload %02X%02X%02X%02X %02X%02X%02X%02X %02X%02X%02X%02X %02X%02X%02X%02X", new Object[]{Byte.valueOf(payload[0]), Byte.valueOf(payload[1]), Byte.valueOf(payload[2]), Byte.valueOf(payload[3]), Byte.valueOf(payload[4]), Byte.valueOf(payload[5]), Byte.valueOf(payload[6]), Byte.valueOf(payload[7]), Byte.valueOf(payload[8]), Byte.valueOf(payload[9]), Byte.valueOf(payload[10]), Byte.valueOf(payload[11]), Byte.valueOf(payload[12]), Byte.valueOf(payload[13]), Byte.valueOf(payload[14]), Byte.valueOf(mifare.readPages(page)[15])}));
            String encodeToString = Base64.encodeToString(payload, 0, 16, 0);
            Log.i("JSAPI", encodeToString);
            return encodeToString;
        } catch (IOException e) {
            Log.e("JSAPI", "IOException while reading tag...", e);
            this.lastError = "readTag(0x" + Integer.toHexString(page & 0xFF) + ") " + e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        } finally {
            if (nfcA!= null) {
                try {
                    nfcA.close();
                } catch (IOException e22) {
                    Log.e("JSAPI", "Error closing tag...", e22);
                }
            }
        }
    }

    @JavascriptInterface
    public boolean writeTag(byte page, String payload) {
        byte[] data = Base64.decode(payload, 0);
        NfcA nfca = NfcA.get(this.activity.tag);
//        MifareUltralight ultralight = MifareUltralight.get(this.activity.tag);
        try {
            Log.i("JSAPI", "Connecting");
            nfca.connect();
//            ultralight.connect();
            Log.i("JSAPI", "Connected");
            Log.i("JSAPI", String.format("Writing %02X%02X%02X%02X", new Object[]{Byte.valueOf(data[0]), Byte.valueOf(data[1]), Byte.valueOf(data[2]), Byte.valueOf(data[3])}));
            byte[] message = new byte[] {
                    (byte) 0xA2,
                    (byte)(page & 0xFF),
                    data[0], data[1], data[2], data[3]
            };
            byte[] result;
            try {
                result = nfca.transceive(message);
                checkWriteAck(result, page);
            } catch (IOException writeFail) {
                Log.w("JSAPI", "Plain write of page " + page + " failed, retrying with PWD_AUTH: " + writeFail.getMessage());
                try { nfca.close(); } catch (IOException ignore) {}
                nfca.connect();
                authenticate(nfca);
                result = nfca.transceive(message);
                checkWriteAck(result, page);
            }
            Log.i("JSAPI", "Writing Done");
            try {
                Log.i("JSAPI", "Closing");
                nfca.close();
                Log.i("JSAPI", "Closed");
                return true;
            } catch (IOException e) {
                Log.e("JSAPI", "IOException while closing MifareUltralight...", e);
                return false;
            }
        } catch (IOException e2) {
            Log.e("JSAPI", "IOException while writing tag...", e2);
            this.lastError = "writeTag(0x" + Integer.toHexString(page & 0xFF) + ") " + e2.getClass().getSimpleName() + ": " + e2.getMessage();
            try {
                Log.i("JSAPI", "Closing");
                nfca.close();
                Log.i("JSAPI", "Closed");
            } catch (IOException e22) {
                Log.e("JSAPI", "IOException while closing MifareUltralight...", e22);
            }
            return false;
        } catch (Throwable th) {
            this.lastError = "writeTag(0x" + Integer.toHexString(page & 0xFF) + ") " + th.getClass().getSimpleName() + ": " + th.getMessage();
            try {
                Log.i("JSAPI", "Closing");
                nfca.close();
                Log.i("JSAPI", "Closed");
            } catch (IOException e222) {
                Log.e("JSAPI", "IOException while closing MifareUltralight...", e222);
            }
            return false;
        }
    }

    private void checkWriteAck(byte[] result, int page) throws IOException {
        if (result == null || result.length == 0 || (result[0] & 0x0F) != 0x0A) {
            throw new IOException("NAK from tag on write to page 0x" + Integer.toHexString(page)
                    + " (page may be locked)");
        }
    }

    private void callJavaScript(String methodName, Object... params) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("javascript:try{(window.");
        stringBuilder.append(methodName);
        stringBuilder.append("||console.warn.bind(console,'UNHANDLED','");
        stringBuilder.append(methodName);
        stringBuilder.append("'))(");
        for (Object param : params) {
            Object param2 = "";
            if (!(param instanceof String)) {
                param2 = param.toString();
            }
            stringBuilder.append("'");
            stringBuilder.append(param2);
            stringBuilder.append("'");
            stringBuilder.append(",");
        }
        stringBuilder.append("''");
        stringBuilder.append(")}catch(error){console.error('ANDROID APP ERROR',error);}");
        this.web.loadUrl(stringBuilder.toString());
        Log.i("CallJS", stringBuilder.toString());
    }
}
