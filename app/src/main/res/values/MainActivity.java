package com.memviewer;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    private static final int SHIZUKU_CODE = 1001;

    private TextView     mStatus;
    private Spinner      mSpinner;
    private LinearLayout mTable;
    private LinearLayout mEvents;
    private ScrollView   mEventScroll;
    private Handler      mMain = new Handler(Looper.getMainLooper());
    private HandlerThread mThread;
    private Handler      mBg;
    private TabHost      mTabs;

    private int     mPid     = -1;
    private long    mBase    = 0;
    private boolean mRunning = false;
    private SimpleDateFormat mSdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // Valores anteriores para detectar mudanças
    private float mPrevHp     = -999f;
    private float mPrevArmour = -999f;
    private float mPrevX      = -999f;
    private float mPrevY      = -999f;
    private float mPrevZ      = -999f;

    // Offsets confirmados do libGTASA.so
    private static final long OFF_PLAYERS = 0xbdc738L;
    private static final long OFF_CAM_POS = 0x89116cL;
    private static final long PED_HEALTH  = 0x6acL;
    private static final long PED_ARMOUR  = 0x6b0L;
    private static final long PED_FRAME   = 0x18L;
    private static final long FRAME_X     = 0x30L;
    private static final long FRAME_Y     = 0x34L;
    private static final long FRAME_Z     = 0x38L;

    private TextView[] mVals;
    private String[] mLabels = {
        "HP", "Armour", "X", "Y", "Z",
        "Frame ptr", "CamX", "CamY", "CamZ", "PED addr"
    };

    // Leitor genérico
    private EditText mEdtAddr, mEdtOffset;
    private Spinner  mTypeSpinner;
    private TextView mGenResult;
    private LinearLayout mWatchList;
    private List<long[]> mWatches = new ArrayList<>(); // {addr, offset, type}
    private List<TextView> mWatchVals = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF060608);

        // Header fixo
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(20, 50, 20, 12);
        header.setBackgroundColor(0xFF060608);

        TextView title = mono("⚡ MEM VIEWER v2.0", 0xFF00ff88, 18f, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        header.addView(divider(0xFF00ff88, 2));
        space(header, 8);

        mStatus = mono("● Iniciando...", 0xFFffff00, 10f, Typeface.NORMAL);
        header.addView(mStatus);
        space(header, 6);

        Button btnS = btn("[ AUTORIZAR SHIZUKU ]", 0xFF00ff88, 0xFF001a0d);
        btnS.setOnClickListener(v -> requestShizuku());
        header.addView(btnS);
        space(header, 8);

        // Seletor de processo
        header.addView(mono("PROCESSO:", 0xFF00aaff, 10f, Typeface.BOLD));
        mSpinner = new Spinner(this);
        mSpinner.setBackgroundColor(0xFF0d0d1a);
        header.addView(mSpinner);
        space(header, 6);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnR = btn("↻", 0xFF00aaff, 0xFF00001a);
        btnR.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnR.setOnClickListener(v -> loadProcesses());
        Button btnGo = btn("▶ INICIAR", 0xFFffaa00, 0xFF1a0d00);
        btnGo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
        btnGo.setOnClickListener(v -> selectAndStart());
        Button btnStop = btn("■", 0xFFff4444, 0xFF1a0000);
        btnStop.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnStop.setOnClickListener(v -> { mRunning = false; setStatus("■ Parado.", 0xFF888888); });
        btnRow.addView(btnR); btnRow.addView(btnGo); btnRow.addView(btnStop);
        header.addView(btnRow);
        space(header, 8);

        root.addView(header);

        // Tabs
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(0xFF0d0d0d);

        final Button[] tabs = new Button[3];
        final ScrollView[] pages = new ScrollView[3];

        tabs[0] = tabBtn("BRP");
        tabs[1] = tabBtn("GENÉRICO");
        tabs[2] = tabBtn("EVENTOS");
        tabBar.addView(tabs[0]);
        tabBar.addView(tabs[1]);
        tabBar.addView(tabs[2]);
        root.addView(tabBar);

        // Páginas
        for (int i = 0; i < 3; i++) {
            pages[i] = new ScrollView(this);
            pages[i].setBackgroundColor(0xFF060608);
            pages[i].setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            root.addView(pages[i]);
        }

        // Lógica de tabs
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(v -> {
                for (int j = 0; j < 3; j++) {
                    pages[j].setVisibility(j == idx ? View.VISIBLE : View.GONE);
                    tabs[j].setBackgroundColor(j == idx ? 0xFF00ff88 : 0xFF1a1a1a);
                    tabs[j].setTextColor(j == idx ? 0xFF000000 : 0xFF888888);
                }
            });
        }
        tabs[0].setBackgroundColor(0xFF00ff88);
        tabs[0].setTextColor(0xFF000000);

        // ── ABA BRP ──────────────────────────────────────────
        LinearLayout brpPage = new LinearLayout(this);
        brpPage.setOrientation(LinearLayout.VERTICAL);
        brpPage.setPadding(16, 12, 16, 16);
        pages[0].addView(brpPage);

        brpPage.addView(mono("VALORES EM TEMPO REAL:", 0xFF00aaff, 10f, Typeface.BOLD));
        space(brpPage, 6);

        mTable = new LinearLayout(this);
        mTable.setOrientation(LinearLayout.VERTICAL);
        mTable.setBackgroundColor(0xFF0d0d0d);
        mTable.setPadding(14, 10, 14, 10);
        mVals = new TextView[mLabels.length];
        for (int i = 0; i < mLabels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 5, 0, 5);
            TextView lbl = mono(String.format("%-10s", mLabels[i]), 0xFF447744, 10f, Typeface.NORMAL);
            lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            mVals[i] = mono("---", 0xFF00ff88, 10f, Typeface.BOLD);
            mVals[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));
            row.addView(lbl); row.addView(mVals[i]);
            mTable.addView(row);
            if (i < mLabels.length - 1) mTable.addView(divider(0xFF1a1a1a, 1));
        }
        brpPage.addView(mTable);

        // ── ABA GENÉRICO ─────────────────────────────────────
        LinearLayout genPage = new LinearLayout(this);
        genPage.setOrientation(LinearLayout.VERTICAL);
        genPage.setPadding(16, 12, 16, 16);
        pages[1].addView(genPage);

        genPage.addView(mono("LEITOR GENÉRICO DE MEMÓRIA", 0xFF00aaff, 11f, Typeface.BOLD));
        genPage.addView(mono("Funciona com qualquer app/jogo", 0xFF555555, 10f, Typeface.NORMAL));
        space(genPage, 12);

        genPage.addView(mono("BASE ADDRESS (hex, ex: bdc738):", 0xFF888888, 10f, Typeface.NORMAL));
        mEdtAddr = edt("ex: bdc738  (0 = usa base da lib)");
        genPage.addView(mEdtAddr);
        space(genPage, 8);

        genPage.addView(mono("OFFSET (hex, ex: 6ac):", 0xFF888888, 10f, Typeface.NORMAL));
        mEdtOffset = edt("ex: 6ac");
        genPage.addView(mEdtOffset);
        space(genPage, 8);

        genPage.addView(mono("TIPO:", 0xFF888888, 10f, Typeface.NORMAL));
        mTypeSpinner = new Spinner(this);
        mTypeSpinner.setBackgroundColor(0xFF0d0d1a);
        ArrayAdapter<String> typeAdp = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item,
            new String[]{"float", "int32", "int64", "pointer (ptr)", "byte", "string"});
        typeAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mTypeSpinner.setAdapter(typeAdp);
        genPage.addView(mTypeSpinner);
        space(genPage, 12);

        LinearLayout genBtns = new LinearLayout(this);
        genBtns.setOrientation(LinearLayout.HORIZONTAL);

        Button btnRead = btn("[ LER ]", 0xFF00ff88, 0xFF001a0d);
        btnRead.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnRead.setOnClickListener(v -> doRead());

        Button btnWatch = btn("[ + WATCH ]", 0xFFffaa00, 0xFF1a0d00);
        btnWatch.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnWatch.setOnClickListener(v -> addWatch());

        genBtns.addView(btnRead); genBtns.addView(btnWatch);
        genPage.addView(genBtns);
        space(genPage, 10);

        mGenResult = mono("→ resultado aparece aqui", 0xFF00ff88, 13f, Typeface.BOLD);
        mGenResult.setPadding(10, 10, 10, 10);
        mGenResult.setBackgroundColor(0xFF0d0d0d);
        genPage.addView(mGenResult);
        space(genPage, 16);

        genPage.addView(divider(0xFF333333, 1));
        space(genPage, 8);
        genPage.addView(mono("WATCH LIST (atualiza automático):", 0xFF00aaff, 10f, Typeface.BOLD));
        space(genPage, 6);

        mWatchList = new LinearLayout(this);
        mWatchList.setOrientation(LinearLayout.VERTICAL);
        mWatchList.setBackgroundColor(0xFF0d0d0d);
        mWatchList.setPadding(12, 8, 12, 8);
        genPage.addView(mWatchList);

        Button btnClearWatch = btn("[ LIMPAR WATCHES ]", 0xFFff4444, 0xFF1a0000);
        btnClearWatch.setOnClickListener(v -> {
            mWatches.clear(); mWatchVals.clear(); mWatchList.removeAllViews();
        });
        space(genPage, 8);
        genPage.addView(btnClearWatch);

        // ── ABA EVENTOS ──────────────────────────────────────
        LinearLayout evtPage = new LinearLayout(this);
        evtPage.setOrientation(LinearLayout.VERTICAL);
        evtPage.setPadding(16, 12, 16, 16);
        pages[2].addView(evtPage);

        evtPage.addView(mono("EVENTOS DETECTADOS:", 0xFF00aaff, 10f, Typeface.BOLD));
        space(evtPage, 6);

        Button btnClearEvt = btn("[ LIMPAR ]", 0xFFff4444, 0xFF1a0000);
        btnClearEvt.setOnClickListener(v -> mEvents.removeAllViews());
        evtPage.addView(btnClearEvt);
        space(evtPage, 8);

        mEvents = new LinearLayout(this);
        mEvents.setOrientation(LinearLayout.VERTICAL);
        mEvents.setPadding(8, 6, 8, 6);
        evtPage.addView(mEvents);

        setContentView(root);

        mThread = new HandlerThread("MemScan");
        mThread.start();
        mBg = new Handler(mThread.getLooper());

        checkShizuku();
        loadProcesses();
    }

    // ── Leitor genérico ──────────────────────────────────────
    private void doRead() {
        if (mPid < 0) { mGenResult.setText("! Selecione um processo primeiro"); return; }
        mBg.post(() -> {
            try {
                long base = parseHex(mEdtAddr.getText().toString());
                long off  = parseHex(mEdtOffset.getText().toString());
                long addr = (base == 0 ? mBase : base) + off;
                int  type = mTypeSpinner.getSelectedItemPosition();
                String result = readGeneric(mPid, addr, type);
                mMain.post(() -> mGenResult.setText("addr: 0x" + Long.toHexString(addr) + "\n→ " + result));
            } catch (Exception e) {
                mMain.post(() -> mGenResult.setText("Erro: " + e.getMessage()));
            }
        });
    }

    private void addWatch() {
        if (mPid < 0) return;
        try {
            long base = parseHex(mEdtAddr.getText().toString());
            long off  = parseHex(mEdtOffset.getText().toString());
            int  type = mTypeSpinner.getSelectedItemPosition();
            long addr = (base == 0 ? mBase : base) + off;

            long[] w = {addr, off, type};
            mWatches.add(w);

            // Adiciona linha na watch list
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 5, 0, 5);

            String typeName = ((ArrayAdapter<String>)mTypeSpinner.getAdapter())
                .getItem(type);
            TextView lbl = mono("0x"+Long.toHexString(addr)+" ("+typeName+")", 0xFF557755, 9f, Typeface.NORMAL);
            lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView val = mono("---", 0xFF00ff88, 10f, Typeface.BOLD);
            val.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            mWatchVals.add(val);

            row.addView(lbl); row.addView(val);
            mWatchList.addView(row);
            mWatchList.addView(divider(0xFF1a1a1a, 1));
        } catch (Exception e) {
            mGenResult.setText("Erro ao adicionar watch: " + e.getMessage());
        }
    }

    private String readGeneric(int pid, long addr, int type) {
        switch (type) {
            case 0: { // float
                float v = readFloat(pid, addr);
                return Float.isNaN(v) ? "ERRO (sem permissão?)" : String.format("%.6f", v);
            }
            case 1: { // int32
                byte[] b = readMem(pid, addr, 4);
                if (b == null) return "ERRO";
                int v = (b[3]&0xFF)<<24|(b[2]&0xFF)<<16|(b[1]&0xFF)<<8|(b[0]&0xFF);
                return v + " (0x" + Integer.toHexString(v) + ")";
            }
            case 2: { // int64
                long v = readPtr(pid, addr);
                return v + " (0x" + Long.toHexString(v) + ")";
            }
            case 3: { // pointer
                long v = readPtr(pid, addr);
                return "0x" + Long.toHexString(v);
            }
            case 4: { // byte
                byte[] b = readMem(pid, addr, 1);
                if (b == null) return "ERRO";
                return (b[0]&0xFF) + " (0x" + Integer.toHexString(b[0]&0xFF) + ")";
            }
            case 5: { // string
                byte[] b = readMem(pid, addr, 64);
                if (b == null) return "ERRO";
                StringBuilder sb = new StringBuilder();
                for (byte c : b) { if (c == 0) break; sb.append((char)c); }
                return "\"" + sb + "\"";
            }
        }
        return "tipo desconhecido";
    }

    private long parseHex(String s) {
        s = s.trim().replace("0x","").replace("0X","");
        if (s.isEmpty()) return 0;
        return Long.parseLong(s, 16);
    }

    // ── BRP scan ─────────────────────────────────────────────
    private void checkShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                    setStatus("● Shizuku: AUTORIZADO ✓", 0xFF00ff88);
                else setStatus("● Shizuku: clique em AUTORIZAR", 0xFFffff00);
            } else setStatus("● Shizuku não está rodando!", 0xFFff4444);
        } catch (Exception e) { setStatus("● Shizuku: " + e.getMessage(), 0xFFff4444); }
    }

    private void requestShizuku() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                setStatus("● Shizuku: AUTORIZADO ✓", 0xFF00ff88);
            else Shizuku.requestPermission(SHIZUKU_CODE);
        } catch (Exception e) { setStatus("● Shizuku não está rodando!", 0xFFff4444); }
    }

    private void loadProcesses() {
        mBg.post(() -> {
            List<String> list = new ArrayList<>();
            list.add("-- selecione o processo --");
            try {
                File[] dirs = new File("/proc").listFiles();
                if (dirs != null) {
                    Arrays.sort(dirs, Comparator.comparing(File::getName));
                    for (File f : dirs) {
                        if (!f.getName().matches("\\d+")) continue;
                        try {
                            FileInputStream fis = new FileInputStream(new File(f, "cmdline"));
                            byte[] buf = new byte[256];
                            int n = fis.read(buf); fis.close();
                            if (n <= 0) continue;
                            String name = new String(buf, 0, n).replace("\0", " ").trim();
                            if (!name.isEmpty()) list.add(f.getName() + "  " + name);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) { list.add("Erro: " + e.getMessage()); }
            mMain.post(() -> {
                ArrayAdapter<String> a = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, list);
                a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                mSpinner.setAdapter(a);
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).contains("brp") || list.get(i).contains("gtasa")) {
                        mSpinner.setSelection(i); break;
                    }
                }
            });
        });
    }

    private void selectAndStart() {
        String sel = (String) mSpinner.getSelectedItem();
        if (sel == null || sel.startsWith("--")) { setStatus("Selecione um processo!", 0xFFff4444); return; }
        String[] parts = sel.trim().split("\\s+", 2);
        try { mPid = Integer.parseInt(parts[0].trim()); } catch (Exception e) { return; }

        mBg.post(() -> {
            mBase = findBase(mPid, "libGTASA.so");
            long b2 = findBase(mPid, "libil2cpp.so");
            long b3 = findBase(mPid, "libmain.so");
            long b4 = findBase(mPid, "libunity.so");
            String msg = "PID=" + mPid
                + " | libGTASA: " + (mBase > 0 ? "0x"+Long.toHexString(mBase) : "n/a")
                + (b2>0?" | il2cpp:0x"+Long.toHexString(b2):"")
                + (b3>0?" | main:0x"+Long.toHexString(b3):"")
                + (b4>0?" | unity:0x"+Long.toHexString(b4):"");
            setStatus(msg, 0xFF00ff88);
            addEvent("▶ Scan | PID=" + mPid, 0xFF00aaff);
            mRunning = true;
            mPrevHp = mPrevArmour = mPrevX = mPrevY = mPrevZ = -999f;
            startLoop();
        });
    }

    private void startLoop() {
        mBg.post(new Runnable() {
            @Override public void run() {
                if (!mRunning) return;
                try {
                    scanBRP();
                    scanWatches();
                } catch (Exception e) {
                    addEvent("ERRO: " + e.getMessage(), 0xFFff4444);
                }
                mBg.postDelayed(this, 100);
            }
        });
    }

    private void scanBRP() {
        long pedAddr = mBase > 0 ? readPtr(mPid, mBase + OFF_PLAYERS) : 0;
        float hp     = pedAddr > 0 ? readFloat(mPid, pedAddr + PED_HEALTH) : Float.NaN;
        float armour = pedAddr > 0 ? readFloat(mPid, pedAddr + PED_ARMOUR) : Float.NaN;
        long  frame  = pedAddr > 0 ? readPtr(mPid, pedAddr + PED_FRAME)    : 0;
        float x  = frame > 0 ? readFloat(mPid, frame + FRAME_X) : Float.NaN;
        float y  = frame > 0 ? readFloat(mPid, frame + FRAME_Y) : Float.NaN;
        float z  = frame > 0 ? readFloat(mPid, frame + FRAME_Z) : Float.NaN;
        float cx = mBase > 0 ? readFloat(mPid, mBase + OFF_CAM_POS)     : Float.NaN;
        float cy = mBase > 0 ? readFloat(mPid, mBase + OFF_CAM_POS + 4) : Float.NaN;
        float cz = mBase > 0 ? readFloat(mPid, mBase + OFF_CAM_POS + 8) : Float.NaN;

        final long ped=pedAddr, fr=frame;
        final float fhp=hp,farm=armour,fx=x,fy=y,fz=z,fcx=cx,fcy=cy,fcz=cz;
        mMain.post(() -> {
            setVal(0, fmt(fhp),  changed(fhp,  mPrevHp,     0.5f));
            setVal(1, fmt(farm), changed(farm,  mPrevArmour, 0.5f));
            setVal(2, fmt(fx),   changed(fx,    mPrevX,      0.05f));
            setVal(3, fmt(fy),   changed(fy,    mPrevY,      0.05f));
            setVal(4, fmt(fz),   changed(fz,    mPrevZ,      0.05f));
            setVal(5, fr  > 0 ? "0x"+Long.toHexString(fr)  : "null", false);
            setVal(6, fmt(fcx), false);
            setVal(7, fmt(fcy), false);
            setVal(8, fmt(fcz), false);
            setVal(9, ped > 0 ? "0x"+Long.toHexString(ped) : "null", false);
        });

        String ts = mSdf.format(new Date());
        if (!Float.isNaN(hp) && mPrevHp > -900) {
            float d = hp - mPrevHp;
            if (Math.abs(d) > 0.5f) {
                if (d < 0) addEvent(ts + " 💥 DANO! HP " + fmt(mPrevHp) + " → " + fmt(hp) + " (" + String.format("%.1f",d) + ")", 0xFFff2222);
                else       addEvent(ts + " 💊 CURA!  HP " + fmt(mPrevHp) + " → " + fmt(hp) + " (+" + String.format("%.1f",d) + ")", 0xFF44ff44);
            }
            if (hp <= 0 && mPrevHp > 0) addEvent(ts + " ☠ MORREU!", 0xFFff0000);
        }
        if (!Float.isNaN(armour) && mPrevArmour > -900) {
            float d = armour - mPrevArmour;
            if (Math.abs(d) > 0.5f) {
                if (d < 0) addEvent(ts + " 🛡 COLETE -"+String.format("%.1f",Math.abs(d))+" → "+fmt(armour), 0xFFff6600);
                else       addEvent(ts + " 🛡 COLETE +"+String.format("%.1f",d)+" → "+fmt(armour), 0xFFffaa00);
            }
        }
        if (!Float.isNaN(x) && mPrevX > -900) {
            float dx=x-mPrevX, dy2=y-mPrevY;
            float mov=(float)Math.sqrt(dx*dx+dy2*dy2);
            if (mov > 5f) addEvent(ts + " 🏃 MOVE Δ="+String.format("%.1f",mov)+"m → X="+fmt(x)+" Y="+fmt(y), 0xFFaa44ff);
        }
        mPrevHp=hp; mPrevArmour=armour; mPrevX=x; mPrevY=y; mPrevZ=z;
    }

    private void scanWatches() {
        for (int i = 0; i < mWatches.size(); i++) {
            long[] w = mWatches.get(i);
            final String val = readGeneric(mPid, w[0], (int)w[2]);
            final int idx = i;
            mMain.post(() -> {
                if (idx < mWatchVals.size())
                    mWatchVals.get(idx).setText(val);
            });
        }
    }

    private boolean changed(float cur, float prev, float thr) {
        return !Float.isNaN(cur) && prev > -900 && Math.abs(cur-prev) > thr;
    }

    private void setVal(int i, String text, boolean red) {
        if (i >= mVals.length) return;
        mVals[i].setText(text);
        if (red) {
            mVals[i].setTextColor(0xFFff0000);
            mMain.postDelayed(() -> mVals[i].setTextColor(0xFF00ff88), 500);
        } else mVals[i].setTextColor(0xFF00ff88);
    }

    private void addEvent(String msg, int color) {
        mMain.post(() -> {
            TextView tv = mono(msg, color, 10f, Typeface.NORMAL);
            tv.setPadding(0, 4, 0, 4);
            mEvents.addView(tv);
            if (mEvents.getChildCount() > 150) mEvents.removeViewAt(0);
        });
    }

    // ── Memória ──────────────────────────────────────────────
    private long findBase(int pid, String lib) {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/"+pid+"/maps"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains(lib) && line.contains("r-xp")) {
                    br.close();
                    return Long.parseLong(line.split("-")[0], 16);
                }
            }
            br.close();
        } catch (Exception ignored) {}
        return 0;
    }

    private byte[] readMem(int pid, long addr, int len) {
        try {
            RandomAccessFile f = new RandomAccessFile("/proc/"+pid+"/mem","r");
            f.seek(addr); byte[] b = new byte[len]; f.read(b); f.close();
            return b;
        } catch (Exception e) { return null; }
    }

    private float readFloat(int pid, long addr) {
        byte[] b = readMem(pid, addr, 4);
        if (b == null) return Float.NaN;
        int bits = (b[3]&0xFF)<<24|(b[2]&0xFF)<<16|(b[1]&0xFF)<<8|(b[0]&0xFF);
        return Float.intBitsToFloat(bits);
    }

    private long readPtr(int pid, long addr) {
        byte[] b = readMem(pid, addr, 8);
        if (b == null) return 0;
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v<<8)|(b[i]&0xFF);
        return v;
    }

    // ── UI helpers ───────────────────────────────────────────
    private String fmt(float v) { return Float.isNaN(v) ? "NaN" : String.format("%.4f", v); }
    private void setStatus(String msg, int color) { mMain.post(() -> { mStatus.setText(msg); mStatus.setTextColor(color); }); }

    private TextView mono(String t, int c, float s, int style) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextColor(c);
        tv.setTypeface(Typeface.MONOSPACE, style); tv.setTextSize(s);
        return tv;
    }
    private Button btn(String t, int tc, int bg) {
        Button b = new Button(this);
        b.setText(t); b.setTextColor(tc); b.setBackgroundColor(bg);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); b.setTextSize(10f);
        return b;
    }
    private Button tabBtn(String t) {
        Button b = new Button(this);
        b.setText(t); b.setTextColor(0xFF888888); b.setBackgroundColor(0xFF1a1a1a);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); b.setTextSize(10f);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }
    private EditText edt(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(0xFF444444);
        e.setTextColor(0xFF00ff88); e.setBackgroundColor(0xFF0d0d0d);
        e.setTypeface(Typeface.MONOSPACE); e.setTextSize(11f);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setPadding(12, 10, 12, 10);
        return e;
    }
    private View divider(int color, int h) {
        View v = new View(this); v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }
    private void space(LinearLayout p, int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dp)));
        p.addView(v);
    }
    private int dp(int dp) { return (int)(dp * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        super.onDestroy();
        mRunning = false;
        if (mThread != null) mThread.quitSafely();
    }
}
