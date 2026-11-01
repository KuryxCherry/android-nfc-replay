package com.nfcreplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {

    static class Item {
        String title = "";
        String sub = "";
        String uri;
        String mime;
        String pkg;
        String raw;
        long time;
    }

    private NfcAdapter nfc;
    private boolean listening = false;
    private final List<Item> items = new ArrayList<>();
    private ListView list;
    private TextView hint;
    private Button scanBtn;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        hint = findViewById(R.id.hint);
        scanBtn = findViewById(R.id.scan);
        nfc = NfcAdapter.getDefaultAdapter(this);

        adapter = new Adapter();
        list.setAdapter(adapter);
        load();

        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleScan(); }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                replay(items.get(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                menu(pos);
                return true;
            }
        });

        if (nfc == null) {
            scanBtn.setEnabled(false);
            hint.setText("这台手机没有 NFC 硬件");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listening) startReader();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listening && nfc != null) nfc.disableReaderMode(this);
    }

    /* ---------------- 扫描 ---------------- */

    private void toggleScan() {
        if (listening) { stopScan(); return; }
        if (!nfc.isEnabled()) {
            toast("请先在系统设置里打开 NFC");
            try { startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); } catch (Exception ignored) {}
            return;
        }
        listening = true;
        startReader();
        scanBtn.setText("停止扫描");
        hint.setText("请把 NFC 卡贴到手机背面…");
    }

    private void startReader() {
        Bundle opts = new Bundle();
        opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        nfc.enableReaderMode(this, new NfcAdapter.ReaderCallback() {
                    @Override public void onTagDiscovered(final Tag tag) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { onTag(tag); }
                        });
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                        | NfcAdapter.FLAG_READER_NFC_BARCODE
                        | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                opts);
    }

    private void stopScan() {
        if (nfc != null) nfc.disableReaderMode(this);
        listening = false;
        scanBtn.setText("＋ 扫描 NFC 卡");
        hint.setText("");
    }

    private void onTag(Tag tag) {
        NdefMessage msg = null;
        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                msg = ndef.getNdefMessage();
                ndef.close();
            }
        } catch (Exception ignored) { }

        if (msg == null) {
            toast("这张卡没有 NDEF 数据（只有 UID=" + hex(tag.getId()) + "），无法重放");
            return;
        }

        Item it = new Item();
        it.raw = Base64.encodeToString(msg.toByteArray(), Base64.NO_WRAP);
        it.time = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();

        for (NdefRecord r : msg.getRecords()) {
            try {
                // Android Application Record：直接给出包名
                if (r.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE
                        && "android.com:pkg".equals(new String(r.getType(), StandardCharsets.US_ASCII))) {
                    it.pkg = new String(r.getPayload(), StandardCharsets.UTF_8).trim();
                    sb.append("应用包名: ").append(it.pkg).append('\n');
                    continue;
                }
                Uri u = r.toUri();
                if (u != null) {
                    if (it.uri == null) it.uri = u.toString();
                    sb.append("URI: ").append(u).append('\n');
                    continue;
                }
                if (r.getTnf() == NdefRecord.TNF_MIME_MEDIA) {
                    if (it.mime == null) it.mime = r.toMimeType();
                    sb.append("MIME: ").append(it.mime).append('\n');
                    continue;
                }
                if (r.getTnf() == NdefRecord.TNF_WELL_KNOWN
                        && Arrays.equals(r.getType(), NdefRecord.RTD_TEXT)) {
                    sb.append("文本: ").append(new String(r.getPayload(), StandardCharsets.UTF_8)).append('\n');
                    continue;
                }
                sb.append("记录 TNF=").append(r.getTnf()).append('\n');
            } catch (Exception e) {
                sb.append("记录解析失败\n");
            }
        }

        it.title = it.pkg != null ? it.pkg
                : it.uri != null ? it.uri
                : it.mime != null ? "MIME · " + it.mime : "NDEF 数据";
        it.sub = sb.toString().trim();

        items.add(it);
        save();
        adapter.notifyDataSetChanged();
        stopScan();
        toast("已保存，点它就能重放");
    }

    /* ---------------- 重放 ---------------- */

    private void replay(Item it) {
        if (it.raw == null || it.raw.isEmpty()) { toast("没有可用数据"); return; }

        NdefMessage msg;
        try {
            msg = new NdefMessage(Base64.decode(it.raw, Base64.NO_WRAP));
        } catch (Exception e) {
            toast("数据损坏"); return;
        }

        // 1) 最贴近真实的一次：ACTION_NDEF_DISCOVERED + 原始 NDEF
        Intent ndef = new Intent(NfcAdapter.ACTION_NDEF_DISCOVERED);
        ndef.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, new Parcelable[]{msg});
        if (it.uri != null && it.mime != null) ndef.setDataAndType(Uri.parse(it.uri), it.mime);
        else if (it.uri != null) ndef.setData(Uri.parse(it.uri));
        else if (it.mime != null) ndef.setType(it.mime);

        List<Intent> tries = new ArrayList<>();
        if (it.pkg != null) {
            Intent i2 = new Intent(ndef);
            i2.setPackage(it.pkg);
            tries.add(i2);
        }
        tries.add(ndef);

        // 2) 退化成普通的 VIEW
        if (it.uri != null) {
            Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse(it.uri));
            if (it.pkg != null) v.setPackage(it.pkg);
            tries.add(v);
        }

        // 3) 最后兜底：直接打开应用
        if (it.pkg != null) {
            try {
                Intent l = getPackageManager().getLaunchIntentForPackage(it.pkg);
                if (l != null) tries.add(l);
            } catch (Exception ignored) { }
        }

        for (Intent i : tries) {
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
        toast("没有应用能响应这条数据（可能是靠 UID 识别的卡）");
    }

    /* ---------------- 列表 / 存储 ---------------- */

    private void menu(final int pos) {
        final Item it = items.get(pos);
        new AlertDialog.Builder(this)
                .setTitle(it.title)
                .setItems(new String[]{"重放一次", "复制数据", "删除"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                if (w == 0) replay(it);
                                else if (w == 1) copy(it);
                                else {
                                    items.remove(pos);
                                    save();
                                    adapter.notifyDataSetChanged();
                                }
                            }
                        })
                .show();
    }

    private void copy(Item it) {
        String text = it.uri != null ? it.uri : it.pkg != null ? it.pkg : it.raw;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("nfc", text));
        toast("已复制到剪贴板");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("tags", MODE_PRIVATE);
    }

    private void load() {
        items.clear();
        try {
            JSONArray arr = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.title = o.optString("title");
                it.sub = o.optString("sub");
                it.uri = o.isNull("uri") ? null : o.optString("uri", null);
                it.mime = o.isNull("mime") ? null : o.optString("mime", null);
                it.pkg = o.isNull("pkg") ? null : o.optString("pkg", null);
                it.raw = o.optString("raw");
                it.time = o.optLong("time");
                items.add(it);
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("title", it.title);
                o.put("sub", it.sub);
                o.put("uri", it.uri == null ? JSONObject.NULL : it.uri);
                o.put("mime", it.mime == null ? JSONObject.NULL : it.mime);
                o.put("pkg", it.pkg == null ? JSONObject.NULL : it.pkg);
                o.put("raw", it.raw);
                o.put("time", it.time);
                arr.put(o);
            }
            prefs().edit().putString("list", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_tag, parent, false);
            }
            Item it = items.get(i);
            ((TextView) v.findViewById(R.id.title)).setText(it.title);
            ((TextView) v.findViewById(R.id.sub)).setText(it.sub);
            return v;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : data) sb.append(String.format("%02X", x));
        return sb.toString();
    }
}
