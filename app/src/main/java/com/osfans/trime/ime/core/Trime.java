/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.osfans.trime.ime.core;

import static android.graphics.Color.parseColor;

import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.StrictMode;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.PopupWindow;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.blankj.utilcode.util.BarUtils;
import com.osfans.trime.BuildConfig;
import com.osfans.trime.R;
import com.osfans.trime.core.Rime;
import com.osfans.trime.data.AppPrefs;
import com.osfans.trime.data.db.DraftHelper;
import com.osfans.trime.data.sound.SoundThemeManager;
import com.osfans.trime.data.theme.Config;
import com.osfans.trime.databinding.CompositionRootBinding;
import com.osfans.trime.databinding.InputRootBinding;
import com.osfans.trime.ime.broadcast.IntentReceiver;
import com.osfans.trime.ime.enums.Keycode;
import com.osfans.trime.ime.enums.PopupPosition;
import com.osfans.trime.ime.enums.SymbolKeyboardType;
import com.osfans.trime.ime.keyboard.Event;
import com.osfans.trime.ime.keyboard.InputFeedbackManager;
import com.osfans.trime.ime.keyboard.Key;
import com.osfans.trime.ime.keyboard.Keyboard;
import com.osfans.trime.ime.keyboard.KeyboardSwitcher;
import com.osfans.trime.ime.keyboard.KeyboardView;
import com.osfans.trime.ime.lifecycle.LifecycleInputMethodService;
import com.osfans.trime.ime.symbol.LiquidKeyboard;
import com.osfans.trime.ime.symbol.TabManager;
import com.osfans.trime.ime.symbol.TabView;
import com.osfans.trime.ime.text.Candidate;
import com.osfans.trime.ime.text.Composition;
import com.osfans.trime.ime.text.ScrollView;
import com.osfans.trime.ime.text.TextInputManager;
import com.osfans.trime.util.DimensionsKt;
import com.osfans.trime.util.ShortcutUtils;
import com.osfans.trime.util.StringUtils;
import com.osfans.trime.util.ViewUtils;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import splitties.bitflags.BitFlagsKt;
import splitties.systemservices.SystemServicesKt;
import timber.log.Timber;

/** {@link InputMethodService ?????????}????????? */
public class Trime extends LifecycleInputMethodService {
  private static Trime self = null;
  private LiquidKeyboard liquidKeyboard;
  private boolean normalTextEditor;

  @NonNull
  private AppPrefs getPrefs() {
    return AppPrefs.defaultInstance();
  }

  /** ??????????????? */
  @NonNull
  public Config getImeConfig() {
    return Config.get();
  }

  private boolean darkMode; // ??????????????????????????????????????????
  private KeyboardView mainKeyboardView; // ????????????

  private Candidate mCandidate; // ??????
  private Composition mComposition; // ??????
  private CompositionRootBinding compositionRootBinding = null;
  private ScrollView mCandidateRoot, mTabRoot;
  private TabView tabView;
  public InputRootBinding inputRootBinding = null;
  public CopyOnWriteArrayList<EventListener> eventListeners = new CopyOnWriteArrayList<>();
  public InputFeedbackManager inputFeedbackManager = null; // ???????????????
  private IntentReceiver mIntentReceiver = null;

  public EditorInfo editorInfo = null;

  private boolean isWindowShown = false; // ???????????????????????????

  private boolean isAutoCaps; // ??????????????????

  private int oneHandMode = 0; // ??????????????????
  public EditorInstance activeEditorInstance;
  public TextInputManager textInputManager; // ?????????????????????

  private boolean isPopupWindowEnabled = true; // ??????????????????
  private String isPopupWindowMovable; // ???????????????????????????
  private int popupWindowX, popupWindowY; // ?????????????????????
  private int popupMargin; // ????????????????????????
  private int popupMarginH; // ?????????????????????????????????
  private boolean isCursorUpdated = false; // ??????????????????
  private int minPopupSize; // ???????????????????????????????????????
  private int minPopupCheckSize; // ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????1??????min_length???????????????
  private PopupPosition popupWindowPos; // ????????????????????????
  private PopupWindow mPopupWindow;
  private RectF mPopupRectF = new RectF();
  private final Handler mPopupHandler = new Handler(Looper.getMainLooper());
  private final Runnable mPopupTimer =
      new Runnable() {
        @Override
        public void run() {
          if (mCandidateRoot == null || mCandidateRoot.getWindowToken() == null) return;
          if (!isPopupWindowEnabled) return;
          int x = 0, y = 0;
          final int[] candidateLocation = new int[2];
          mCandidateRoot.getLocationOnScreen(candidateLocation);
          final int minX = popupMarginH;
          final int minY = popupMargin;
          final int maxX = mCandidateRoot.getWidth() - mPopupWindow.getWidth() - minX;
          final int maxY = candidateLocation[1] - mPopupWindow.getHeight() - minY;
          if (isWinFixed() || !isCursorUpdated) {
            // setCandidatesViewShown(true);
            switch (popupWindowPos) {
              case TOP_RIGHT:
                x = maxX;
                y = minY;
                break;
              case TOP_LEFT:
                x = minX;
                y = minY;
                break;
              case BOTTOM_RIGHT:
                x = maxX;
                y = maxY;
                break;
              case DRAG:
                x = popupWindowX;
                y = popupWindowY;
                break;
              case FIXED:
              case BOTTOM_LEFT:
              default:
                x = minX;
                y = maxY;
                break;
            }
          } else {
            // setCandidatesViewShown(false);
            switch (popupWindowPos) {
              case LEFT:
              case LEFT_UP:
                x = (int) mPopupRectF.left;
                break;
              case RIGHT:
              case RIGHT_UP:
                x = (int) mPopupRectF.right;
                break;
              default:
                Timber.wtf("UNREACHABLE BRANCH");
            }
            x = Math.min(maxX, x);
            x = Math.max(minX, x);
            switch (popupWindowPos) {
              case LEFT:
              case RIGHT:
                y = (int) mPopupRectF.bottom + popupMargin;
                break;
              case LEFT_UP:
              case RIGHT_UP:
                y = (int) mPopupRectF.top - mPopupWindow.getHeight() - popupMargin;
                break;
              default:
                Timber.wtf("UNREACHABLE BRANCH");
            }
            y = Math.min(maxY, y);
            y = Math.max(minY, y);
          }
          y -= BarUtils.getStatusBarHeight(); // ??????????????????

          if (!mPopupWindow.isShowing()) {
            mPopupWindow.showAtLocation(mCandidateRoot, Gravity.START | Gravity.TOP, x, y);
          } else {
            mPopupWindow.update(x, y, mPopupWindow.getWidth(), mPopupWindow.getHeight());
          }
        }
      };

  public static Trime getService() {
    return self;
  }

  @Nullable
  public static Trime getServiceOrNull() {
    return self;
  }

  private static final Handler syncBackgroundHandler =
      new Handler(
          msg -> {
            if (!((Trime) msg.obj).isShowInputRequested()) { // ?????????????????????????????????????????????????????????????????????5??????????????????
              ShortcutUtils.INSTANCE.syncInBackground();
              ((Trime) msg.obj).loadConfig();
            }
            return false;
          });

  public Trime() {
    try {
      self = this;
      textInputManager = TextInputManager.Companion.getInstance();
    } catch (Exception e) {
      e.fillInStackTrace();
    }
  }

  @Override
  public void onWindowShown() {
    super.onWindowShown();
    if (isWindowShown) {
      Timber.i("Ignoring (is already shown)");
      return;
    } else {
      Timber.i("onWindowShown...");
    }
    isWindowShown = true;

    updateComposing();

    for (EventListener listener : eventListeners) {
      if (listener != null) listener.onWindowShown();
    }
  }

  @Override
  public void onWindowHidden() {
    String methodName =
        "\t<TrimeInit>\t" + Thread.currentThread().getStackTrace()[2].getMethodName() + "\t";
    Timber.d(methodName);
    super.onWindowHidden();
    Timber.d(methodName + "super finish");
    if (!isWindowShown) {
      Timber.i("Ignoring (is already hidden)");
      return;
    } else {
      Timber.i("onWindowHidden...");
    }
    isWindowShown = false;

    if (getPrefs().getProfile().getSyncBackgroundEnabled()) {
      final Message msg = new Message();
      msg.obj = this;
      syncBackgroundHandler.sendMessageDelayed(msg, 5000); // ??????????????????5???????????????????????????
    }

    Timber.d(methodName + "eventListeners");
    for (EventListener listener : eventListeners) {
      if (listener != null) listener.onWindowHidden();
    }
  }

  private boolean isWinFixed() {
    return VERSION.SDK_INT <= VERSION_CODES.LOLLIPOP
        || (popupWindowPos != PopupPosition.LEFT
            && popupWindowPos != PopupPosition.RIGHT
            && popupWindowPos != PopupPosition.LEFT_UP
            && popupWindowPos != PopupPosition.RIGHT_UP);
  }

  public void updatePopupWindow(final int offsetX, final int offsetY) {
    popupWindowPos = PopupPosition.DRAG;
    popupWindowX = offsetX;
    popupWindowY = offsetY;
    Timber.i("updatePopupWindow: winX = %s, winY = %s", popupWindowX, popupWindowY);
    mPopupWindow.update(popupWindowX, popupWindowY, -1, -1, true);
  }

  public void loadConfig() {
    final Config imeConfig = getImeConfig();
    popupWindowPos = PopupPosition.fromString(imeConfig.style.getString("layout/position"));
    isPopupWindowMovable = imeConfig.style.getString("layout/movable");
    popupMargin = (int) DimensionsKt.dp2px(imeConfig.style.getFloat("layout/spacing"));
    minPopupSize = imeConfig.style.getInt("layout/min_length");
    minPopupCheckSize = imeConfig.style.getInt("layout/min_check");
    popupMarginH = (int) DimensionsKt.dp2px(imeConfig.style.getFloat("layout/real_margin"));
    textInputManager.setShouldResetAsciiMode(imeConfig.style.getBoolean("reset_ascii_mode"));
    isAutoCaps = imeConfig.style.getBoolean("auto_caps");
    isPopupWindowEnabled =
        getPrefs().getKeyboard().getPopupWindowEnabled()
            && imeConfig.style.getObject("window") != null;
    textInputManager.setShouldUpdateRimeOption(true);
  }

  @SuppressWarnings("UnusedReturnValue")
  private boolean updateRimeOption() {
    try {
      if (textInputManager.getShouldUpdateRimeOption()) {
        Rime.setOption("soft_cursor", getPrefs().getKeyboard().getSoftCursorEnabled()); // ?????????
        Rime.setOption("_horizontal", getImeConfig().style.getBoolean("horizontal")); // ????????????
        textInputManager.setShouldUpdateRimeOption(false);
      }
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
    return true;
  }

  @Override
  public void onCreate() {

    StrictMode.setVmPolicy(
        new StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
            .detectLeakedClosableObjects()
            .build());
    String methodName =
        "\t<TrimeInit>\t" + Thread.currentThread().getStackTrace()[2].getMethodName() + "\t";
    Timber.d(methodName);
    // MUST WRAP all code within Service onCreate() in try..catch to prevent any crash loops
    try {
      // Additional try..catch wrapper as the event listeners chain or the super.onCreate() method
      // could crash
      //  and lead to a crash loop
      try {
        Timber.i("onCreate...");

        activeEditorInstance = new EditorInstance(this);
        Timber.d(methodName + "InputFeedbackManager");
        inputFeedbackManager = new InputFeedbackManager(this);

        Timber.d(methodName + "liquidKeyboard");
        liquidKeyboard = new LiquidKeyboard(this);
      } catch (Exception e) {
        e.printStackTrace();
        super.onCreate();
        return;
      }
      Timber.d(methodName + "super.onCreate()");
      super.onCreate();
      Timber.d(methodName + "create listener");
      for (EventListener listener : eventListeners) {
        if (listener != null) listener.onCreate();
      }
    } catch (Exception e) {
      e.fillInStackTrace();
    }
    Timber.d(methodName + "finish");
  }

  /**
   * ?????????????????????????????????
   *
   * @param darkMode ?????????????????????
   * @return ????????????????????????????????????
   */
  public boolean setDarkMode(boolean darkMode) {
    if (darkMode != this.darkMode) {
      Timber.i("setDarkMode " + darkMode);
      this.darkMode = darkMode;
      return true;
    }
    return false;
  }

  private SymbolKeyboardType symbolKeyboardType = SymbolKeyboardType.NO_KEY;

  public void inputSymbol(final String text) {
    textInputManager.onPress(KeyEvent.KEYCODE_UNKNOWN);
    if (Rime.isAsciiMode()) Rime.setOption("ascii_mode", false);
    boolean asciiPunch = Rime.isAsciiPunch();
    if (asciiPunch) Rime.setOption("ascii_punct", false);
    textInputManager.onText("{Escape}" + text);
    if (asciiPunch) Rime.setOption("ascii_punct", true);
    Trime.getService().selectLiquidKeyboard(-1);
  }

  public void selectLiquidKeyboard(final int tabIndex) {
    if (inputRootBinding == null) return;
    final View symbolInput = inputRootBinding.symbol.symbolInput;
    final View mainInput = inputRootBinding.main.mainInput;
    if (tabIndex >= 0) {
      symbolInput.getLayoutParams().height = mainInput.getHeight();
      symbolInput.setVisibility(View.VISIBLE);

      symbolKeyboardType = liquidKeyboard.select(tabIndex);
      tabView.updateTabWidth();
      if (inputRootBinding != null) {
        mTabRoot.setBackground(mCandidateRoot.getBackground());
        mTabRoot.move(tabView.getHightlightLeft(), tabView.getHightlightRight());
      }
    } else {
      symbolKeyboardType = SymbolKeyboardType.NO_KEY;
      symbolInput.setVisibility(View.GONE);
    }
    updateComposing();
    mainInput.setVisibility(tabIndex >= 0 ? View.GONE : View.VISIBLE);
  }

  // ??????????????????tab name?????????liquidKeyboard?????????tab
  public void selectLiquidKeyboard(@NonNull String name) {
    if (name.matches("-?\\d+")) selectLiquidKeyboard(Integer.parseInt(name));
    else if (name.matches("[A-Z]+")) selectLiquidKeyboard(SymbolKeyboardType.valueOf(name));
    else selectLiquidKeyboard(TabManager.getTagIndex(name));
  }

  public void selectLiquidKeyboard(SymbolKeyboardType type) {
    selectLiquidKeyboard(TabManager.getTagIndex(type));
  }

  public void pasteByChar() {
    commitTextByChar(Objects.requireNonNull(ShortcutUtils.pasteFromClipboard(this)).toString());
  }

  public void invalidate() {
    Rime.get();
    getImeConfig().destroy();
    reset();
    textInputManager.setShouldUpdateRimeOption(true);
  }

  private void hideCompositionView() {
    if (isPopupWindowMovable != null && isPopupWindowMovable.equals("once")) {
      popupWindowPos = PopupPosition.fromString(getImeConfig().style.getString("layout/position"));
    }

    if (mPopupWindow != null && mPopupWindow.isShowing()) {
      mPopupWindow.dismiss();
      mPopupHandler.removeCallbacks(mPopupTimer);
    }
  }

  private void showCompositionView(boolean isCandidate) {
    if (TextUtils.isEmpty(Rime.getCompositionText()) && isCandidate) {
      hideCompositionView();
      return;
    }
    compositionRootBinding.compositionRoot.measure(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    mPopupWindow.setWidth(compositionRootBinding.compositionRoot.getMeasuredWidth());
    mPopupWindow.setHeight(compositionRootBinding.compositionRoot.getMeasuredHeight());
    mPopupHandler.post(mPopupTimer);
  }

  public void loadBackground() {
    final Config mConfig = getImeConfig();
    final int orientation = getResources().getConfiguration().orientation;

    if (mPopupWindow != null) {
      final Drawable textBackground =
          mConfig.colors.getDrawable(
              "text_back_color",
              "layout/border",
              "border_color",
              "layout/round_corner",
              "layout/alpha");
      if (textBackground != null) mPopupWindow.setBackgroundDrawable(textBackground);
      mPopupWindow.setElevation(
          (int) DimensionsKt.dp2px(mConfig.style.getFloat("layout/elevation")));
    }

    if (mCandidateRoot != null) {
      final Drawable candidateBackground =
          mConfig.colors.getDrawable(
              "candidate_background",
              "candidate_border",
              "candidate_border_color",
              "candidate_border_round",
              null);
      if (candidateBackground != null) mCandidateRoot.setBackground(candidateBackground);
    }

    if (inputRootBinding == null) return;

    int[] padding =
        mConfig.getKeyboardPadding(oneHandMode, orientation == Configuration.ORIENTATION_LANDSCAPE);
    Timber.i(
        "update KeyboardPadding: Trime.loadBackground, padding= %s %s %s, orientation=%s",
        padding[0], padding[1], padding[2], orientation);
    mainKeyboardView.setPadding(padding[0], 0, padding[1], padding[2]);

    final Drawable inputRootBackground = mConfig.colors.getDrawable("root_background");
    if (inputRootBackground != null) {
      inputRootBinding.inputRoot.setBackground(inputRootBackground);
    } else {
      // ????????????????????????????????????????????????
      inputRootBinding.inputRoot.setBackgroundColor(Color.BLACK);
    }

    tabView.reset();
  }

  public void resetKeyboard() {
    if (mainKeyboardView != null) {
      mainKeyboardView.setShowHint(!Rime.getOption("_hide_key_hint"));
      mainKeyboardView.setShowSymbol(!Rime.getOption("_hide_key_symbol"));
      mainKeyboardView.reset(); // ????????????????????????
    }
  }

  public void resetCandidate() {
    if (mCandidateRoot != null) {
      loadBackground();
      setShowComment(!Rime.getOption("_hide_comment"));
      mCandidateRoot.setVisibility(!Rime.getOption("_hide_candidate") ? View.VISIBLE : View.GONE);
      mCandidate.reset();
      isPopupWindowEnabled =
          getPrefs().getKeyboard().getPopupWindowEnabled()
              && getImeConfig().style.getObject("window") != null;
      mComposition.setVisibility(isPopupWindowEnabled ? View.VISIBLE : View.GONE);
      mComposition.reset();
    }
  }

  /** ??????????????????????????????????????? !!???????????????????????????Rime.setOption???????????????????????? */
  private void reset() {
    if (inputRootBinding == null) return;
    inputRootBinding.symbol.symbolInput.setVisibility(View.GONE);
    inputRootBinding.main.mainInput.setVisibility(View.VISIBLE);
    loadConfig();
    getImeConfig().initCurrentColors();
    SoundThemeManager.switchSound(getImeConfig().colors.getString("sound"));
    KeyboardSwitcher.newOrReset();
    resetCandidate();
    hideCompositionView();
    resetKeyboard();
  }

  /** Must be called on the UI thread */
  public void initKeyboard() {
    reset();
    // setNavBarColor();
    textInputManager.setShouldUpdateRimeOption(true); // ?????????Rime.onMessage?????????set_option????????????
    bindKeyboardToInputView();
    // loadBackground(); // reset()?????????resetCandidate()???resetCandidate()???????????????loadBackground();
    updateComposing(); // ???????????????????????????
  }

  public void initKeyboardDarkMode(boolean darkMode) {
    if (getImeConfig().hasDarkLight()) {
      loadConfig();
      getImeConfig().initCurrentColors(darkMode);
      SoundThemeManager.switchSound(getImeConfig().colors.getString("sound"));
      KeyboardSwitcher.newOrReset();
      resetCandidate();
      hideCompositionView();
      resetKeyboard();

      // setNavBarColor();
      textInputManager.setShouldUpdateRimeOption(true); // ?????????Rime.onMessage?????????set_option????????????
      bindKeyboardToInputView();
      // loadBackground(); // reset()?????????resetCandidate()???resetCandidate()???????????????loadBackground();
      updateComposing(); // ???????????????????????????
    }
  }

  @Override
  public void onDestroy() {
    if (mIntentReceiver != null) mIntentReceiver.unregisterReceiver(this);
    mIntentReceiver = null;
    if (inputFeedbackManager != null) inputFeedbackManager.destroy();
    inputFeedbackManager = null;
    inputRootBinding = null;

    for (EventListener listener : eventListeners) {
      if (listener != null) listener.onDestroy();
    }
    eventListeners.clear();
    super.onDestroy();

    self = null;
  }

  private void handleReturnKey() {
    if (editorInfo == null) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
    if ((editorInfo.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL) {
      sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
    }
    if (BitFlagsKt.hasFlag(editorInfo.imeOptions, EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
      final InputConnection ic = getCurrentInputConnection();
      if (ic != null) ic.commitText("\n", 1);
      return;
    }
    if (!TextUtils.isEmpty(editorInfo.actionLabel)
        && editorInfo.actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
      final InputConnection ic = getCurrentInputConnection();
      if (ic != null) ic.performEditorAction(editorInfo.actionId);
      return;
    }
    final int action = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
    final InputConnection ic = getCurrentInputConnection();
    switch (action) {
      case EditorInfo.IME_ACTION_UNSPECIFIED:
      case EditorInfo.IME_ACTION_NONE:
        if (ic != null) ic.commitText("\n", 1);
        break;
      default:
        if (ic != null) ic.performEditorAction(action);
        break;
    }
  }

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfig) {
    final Configuration config = getResources().getConfiguration();
    if (config != null) {
      if (config.orientation != newConfig.orientation) {
        // Clear composing text and candidates for orientation change.
        performEscape();
        config.orientation = newConfig.orientation;
      }
    }
    super.onConfigurationChanged(newConfig);
  }

  @Override
  public void onUpdateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
    if (!isWinFixed()) {
      final CharSequence composingText = cursorAnchorInfo.getComposingText();
      // update mPopupRectF
      if (composingText == null) {
        // composing is disabled in target app or trime settings
        // use the position of the insertion marker instead
        mPopupRectF.top = cursorAnchorInfo.getInsertionMarkerTop();
        mPopupRectF.left = cursorAnchorInfo.getInsertionMarkerHorizontal();
        mPopupRectF.bottom = cursorAnchorInfo.getInsertionMarkerBottom();
        mPopupRectF.right = mPopupRectF.left;
      } else {
        final int startPos = cursorAnchorInfo.getComposingTextStart();
        final int endPos = startPos + composingText.length() - 1;
        final RectF startCharRectF = cursorAnchorInfo.getCharacterBounds(startPos);
        final RectF endCharRectF = cursorAnchorInfo.getCharacterBounds(endPos);
        if (startCharRectF == null || endCharRectF == null) {
          // composing text has been changed, the next onUpdateCursorAnchorInfo is on the road
          // ignore this outdated update
          return;
        }
        // for different writing system (e.g. right to left languages),
        // we have to calculate the correct RectF
        mPopupRectF.top = Math.min(startCharRectF.top, endCharRectF.top);
        mPopupRectF.left = Math.min(startCharRectF.left, endCharRectF.left);
        mPopupRectF.bottom = Math.max(startCharRectF.bottom, endCharRectF.bottom);
        mPopupRectF.right = Math.max(startCharRectF.right, endCharRectF.right);
      }
      cursorAnchorInfo.getMatrix().mapRect(mPopupRectF);
    }
    if (mCandidateRoot != null) {
      showCompositionView(true);
    }
  }

  @Override
  public void onUpdateSelection(
      int oldSelStart,
      int oldSelEnd,
      int newSelStart,
      int newSelEnd,
      int candidatesStart,
      int candidatesEnd) {
    super.onUpdateSelection(
        oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    if ((candidatesEnd != -1) && ((newSelStart != candidatesEnd) || (newSelEnd != candidatesEnd))) {
      // ?????????????????????????????????
      if ((newSelEnd < candidatesEnd) && (newSelEnd >= candidatesStart)) {
        final int n = newSelEnd - candidatesStart;
        Rime.RimeSetCaretPos(n);
        updateComposing();
      }
    }
    if ((candidatesStart == -1 && candidatesEnd == -1) && (newSelStart == 0 && newSelEnd == 0)) {
      // ???????????????????????????
      performEscape();
    }
    // Update the caps-lock status for the current cursor position.
    dispatchCapsStateToInputView();
  }

  @Override
  public void onComputeInsets(InputMethodService.Insets outInsets) {
    super.onComputeInsets(outInsets);
    outInsets.contentTopInsets = outInsets.visibleTopInsets;
  }

  @Override
  public View onCreateInputView() {
    Timber.e("onCreateInputView()");
    // ?????????????????????
    super.onCreateInputView();
    inputRootBinding = InputRootBinding.inflate(LayoutInflater.from(this));
    mainKeyboardView = inputRootBinding.main.mainKeyboardView;

    // ??????????????????
    mCandidateRoot = inputRootBinding.main.candidateView.candidateRoot;
    mCandidate = inputRootBinding.main.candidateView.candidates;

    // ???????????????????????????
    compositionRootBinding = CompositionRootBinding.inflate(LayoutInflater.from(this));
    mComposition = compositionRootBinding.compositions;
    mPopupWindow = new PopupWindow(compositionRootBinding.compositionRoot);
    mPopupWindow.setClippingEnabled(false);
    mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    if (VERSION.SDK_INT >= VERSION_CODES.M) {
      mPopupWindow.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);
    }
    hideCompositionView();
    mTabRoot = inputRootBinding.symbol.tabView.tabRoot;

    liquidKeyboard.setKeyboardView(inputRootBinding.symbol.liquidKeyboardView);
    tabView = inputRootBinding.symbol.tabView.tabs;

    for (EventListener listener : eventListeners) {
      assert inputRootBinding != null;
      if (listener != null) listener.onInitializeInputUi(inputRootBinding);
    }
    getImeConfig().initCurrentColors();
    loadBackground();

    KeyboardSwitcher.newOrReset();
    Timber.i("onCreateInputView() finish");

    return inputRootBinding.inputRoot;
  }

  public void setShowComment(boolean show_comment) {
    if (mCandidateRoot != null) mCandidate.setShowComment(show_comment);
    mComposition.setShowComment(show_comment);
  }

  @Override
  public void onStartInput(EditorInfo attribute, boolean restarting) {
    editorInfo = attribute;
    Timber.d("onStartInput: restarting=%s", restarting);
  }

  @Override
  public void onStartInputView(EditorInfo attribute, boolean restarting) {
    Timber.d("onStartInputView: restarting=%s", restarting);
    editorInfo = attribute;
    if (getPrefs().getThemeAndColor().getAutoDark()) {
      int nightModeFlags =
          getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
      if (setDarkMode(nightModeFlags == Configuration.UI_MODE_NIGHT_YES)) {
        Timber.i("dark mode changed");
        initKeyboardDarkMode(darkMode);
      } else Timber.i("dark mode not changed");
    } else {
      Timber.i("auto dark off");
    }

    inputFeedbackManager.resumeSoundPool();
    inputFeedbackManager.resetPlayProgress();
    for (EventListener listener : eventListeners) {
      if (listener != null) listener.onStartInputView(activeEditorInstance, restarting);
    }
    if (getPrefs().getOther().getShowStatusBarIcon()) {
      showStatusIcon(R.drawable.ic_trime_status); // ???????????????
    }
    bindKeyboardToInputView();
    // if (!restarting) setNavBarColor();
    setCandidatesViewShown(!Rime.isEmpty()); // ?????????????????????????????????

    if ((attribute.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        == EditorInfo.IME_FLAG_NO_ENTER_ACTION) {
      mainKeyboardView.resetEnterLabel();
    } else {
      mainKeyboardView.setEnterLabel(
          attribute.imeOptions & EditorInfo.IME_MASK_ACTION, attribute.actionLabel);
    }

    switch (attribute.inputType & InputType.TYPE_MASK_VARIATION) {
      case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
      case InputType.TYPE_TEXT_VARIATION_PASSWORD:
      case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
      case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
      case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
        Timber.i(
            "EditorInfo: private;"
                + " packageName="
                + attribute.packageName
                + "; fieldName="
                + attribute.fieldName
                + "; actionLabel="
                + attribute.actionLabel
                + "; inputType="
                + attribute.inputType
                + "; VARIATION="
                + (attribute.inputType & InputType.TYPE_MASK_VARIATION)
                + "; CLASS="
                + (attribute.inputType & InputType.TYPE_MASK_CLASS)
                + "; ACTION="
                + (attribute.imeOptions & EditorInfo.IME_MASK_ACTION));
        normalTextEditor = false;
        break;

      default:
        Timber.i(
            "EditorInfo: normal;"
                + " packageName="
                + attribute.packageName
                + "; fieldName="
                + attribute.fieldName
                + "; actionLabel="
                + attribute.actionLabel
                + "; inputType="
                + attribute.inputType
                + "; VARIATION="
                + (attribute.inputType & InputType.TYPE_MASK_VARIATION)
                + "; CLASS="
                + (attribute.inputType & InputType.TYPE_MASK_CLASS)
                + "; ACTION="
                + (attribute.imeOptions & EditorInfo.IME_MASK_ACTION));

        if ((attribute.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
            == EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) {
          //  ???????????????????????????????????????????????????
          normalTextEditor = false;
          Timber.i("EditorInfo: normal -> private, IME_FLAG_NO_PERSONALIZED_LEARNING");
        } else if (attribute.packageName.equals(BuildConfig.APPLICATION_ID)
            || getPrefs().getClipboard().getDraftExcludeApp().contains(attribute.packageName)) {
          normalTextEditor = false;
          Timber.i("EditorInfo: normal -> exclude, packageName=" + attribute.packageName);
        } else {
          normalTextEditor = true;
          DraftHelper.INSTANCE.onInputEventChanged();
        }
    }
  }

  @Override
  public void onFinishInputView(boolean finishingInput) {
    if (normalTextEditor) {
      DraftHelper.INSTANCE.onInputEventChanged();
    }
    super.onFinishInputView(finishingInput);
    // Dismiss any pop-ups when the input-view is being finished and hidden.
    mainKeyboardView.closing();
    performEscape();
    inputFeedbackManager.releaseSoundPool();
    try {
      hideCompositionView();
    } catch (Exception e) {
      Timber.e(e, "Failed to show the PopupWindow.");
    }
  }

  @Override
  public void onFinishInput() {
    editorInfo = null;
    super.onFinishInput();
  }

  public void bindKeyboardToInputView() {
    if (mainKeyboardView != null) {
      // Bind the selected keyboard to the input view.
      Keyboard sk = KeyboardSwitcher.getCurrentKeyboard();
      mainKeyboardView.setKeyboard(sk);
      dispatchCapsStateToInputView();
    }
  }

  /**
   * Dispatches cursor caps info to input view in order to implement auto caps lock at the start of
   * a sentence.
   */
  private void dispatchCapsStateToInputView() {
    if ((isAutoCaps && Rime.isAsciiMode())
        && (mainKeyboardView != null && !mainKeyboardView.isCapsOn())) {
      mainKeyboardView.setShifted(false, activeEditorInstance.getCursorCapsMode() != 0);
    }
  }

  private boolean isComposing() {
    return Rime.isComposing();
  }

  public void commitText(String text) {
    activeEditorInstance.commitText(text, true);
  }

  public void commitTextByChar(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (!activeEditorInstance.commitText(text.substring(i, i + 1), false)) break;
    }
  }

  /**
   * ?????????{@link KeyEvent#KEYCODE_BACK Back???}??????????????????
   *
   * @param keyCode {@link KeyEvent#getKeyCode() ??????}
   * @return ???????????????Back?????????
   */
  private boolean handleBack(int keyCode) {
    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
      requestHideSelf(0);
      return true;
    }
    return false;
  }

  public boolean onRimeKey(int[] event) {
    updateRimeOption();
    // todo ???????????????????????????????????????UI
    final boolean ret = Rime.processKey(event[0], event[1]);
    activeEditorInstance.commitRimeText();
    return ret;
  }

  private boolean composeEvent(@NonNull KeyEvent event) {
    final int keyCode = event.getKeyCode();
    if (keyCode == KeyEvent.KEYCODE_MENU) return false; // ????????? Menu ???
    if (!Keycode.Companion.isStdKey(keyCode)) return false; // ???????????????????????????
    if (event.getRepeatCount() == 0 && Key.isTrimeModifierKey(keyCode)) {
      boolean ret =
          onRimeKey(
              Event.getRimeEvent(
                  keyCode,
                  event.getAction() == KeyEvent.ACTION_DOWN
                      ? event.getModifiers()
                      : Rime.META_RELEASE_ON));
      if (isComposing()) setCandidatesViewShown(textInputManager.isComposable()); // ????????????????????????????????????
      return ret;
    }
    return textInputManager.isComposable() && !Rime.isVoidKeycode(keyCode);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    Timber.i("\t<TrimeInput>\tonKeyDown()\tkeycode=%d, event=%s", keyCode, event.toString());
    if (composeEvent(event) && onKeyEvent(event)) return true;
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    Timber.i("\t<TrimeInput>\tonKeyUp()\tkeycode=%d, event=%s", keyCode, event.toString());
    if (composeEvent(event) && textInputManager.getNeedSendUpRimeKey()) {
      textInputManager.onRelease(keyCode);
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  /**
   * ????????????????????????
   *
   * @param event {@link KeyEvent ????????????}
   * @return ??????????????????
   */
  // KeyEvent ????????????????????????
  private boolean onKeyEvent(@NonNull KeyEvent event) {
    Timber.i("\t<TrimeInput>\tonKeyEvent()\tRealKeyboard event=%s", event.toString());
    int keyCode = event.getKeyCode();
    textInputManager.setNeedSendUpRimeKey(Rime.isComposing());
    if (!isComposing()) {
      if (keyCode == KeyEvent.KEYCODE_DEL
          || keyCode == KeyEvent.KEYCODE_ENTER
          || keyCode == KeyEvent.KEYCODE_ESCAPE
          || keyCode == KeyEvent.KEYCODE_BACK) {
        return false;
      }
    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
      keyCode = KeyEvent.KEYCODE_ESCAPE; // ???????????????
    }
    if (event.getAction() == KeyEvent.ACTION_DOWN
        && event.isCtrlPressed()
        && event.getRepeatCount() == 0
        && !KeyEvent.isModifierKey(keyCode)) {
      if (hookKeyboard(keyCode, event.getMetaState())) return true;
    }

    final int unicodeChar = event.getUnicodeChar();
    int mask = event.getMetaState();
    if (unicodeChar > 0) {
      keyCode = unicodeChar;
    }
    final boolean ret = handleKey(keyCode, mask);
    if (isComposing()) setCandidatesViewShown(textInputManager.isComposable()); // ????????????????????????????????????
    return ret;
  }

  public void switchToPrevIme() {
    try {
      if (VERSION.SDK_INT >= VERSION_CODES.P) {
        switchToPreviousInputMethod();
      } else {
        Window window = getWindow().getWindow();
        if (window != null) {
          SystemServicesKt.getInputMethodManager()
              .switchToLastInputMethod(window.getAttributes().token);
        }
      }
    } catch (Exception e) {
      Timber.e(e, "Unable to switch to the previous IME.");
      SystemServicesKt.getInputMethodManager().showInputMethodPicker();
    }
  }

  public void switchToNextIme() {
    try {
      if (VERSION.SDK_INT >= VERSION_CODES.P) {
        switchToNextInputMethod(false);
      } else {
        Window window = getWindow().getWindow();
        if (window != null) {
          SystemServicesKt.getInputMethodManager()
              .switchToNextInputMethod(window.getAttributes().token, false);
        }
      }
    } catch (Exception e) {
      Timber.e(e, "Unable to switch to the next IME.");
      SystemServicesKt.getInputMethodManager().showInputMethodPicker();
    }
  }

  // ??????????????????(Android keycode)
  public boolean handleKey(int keyEventCode, int metaState) { // ?????????
    textInputManager.setNeedSendUpRimeKey(false);
    if (onRimeKey(Event.getRimeEvent(keyEventCode, metaState))) {
      // ????????????????????????????????????????????????????????????
      textInputManager.setNeedSendUpRimeKey(true);
      Timber.d(
          "\t<TrimeInput>\thandleKey()\trimeProcess, keycode=%d, metaState=%d",
          keyEventCode, metaState);
    } else if (hookKeyboard(keyEventCode, metaState)) {
      Timber.d("\t<TrimeInput>\thandleKey()\thookKeyboard, keycode=%d", keyEventCode);
    } else if (performEnter(keyEventCode) || handleBack(keyEventCode)) {
      // ????????????????????????????????????????????????????????????
      // todo ????????????????????????????????????????????????????????????back???escape???????????????
      Timber.d("\t<TrimeInput>\thandleKey()\tEnterOrHide, keycode=%d", keyEventCode);
    } else if (ShortcutUtils.INSTANCE.openCategory(keyEventCode)) {
      // ????????????????????????
      Timber.d("\t<TrimeInput>\thandleKey()\topenCategory keycode=%d", keyEventCode);
    } else {
      textInputManager.setNeedSendUpRimeKey(true);
      Timber.d(
          "\t<TrimeInput>\thandleKey()\treturn FALSE, keycode=%d, metaState=%d",
          keyEventCode, metaState);
      return false;
    }
    return true;
  }

  public boolean shareText() {
    if (VERSION.SDK_INT >= VERSION_CODES.M) {
      final @Nullable InputConnection ic = getCurrentInputConnection();
      if (ic == null) return false;
      CharSequence cs = ic.getSelectedText(0);
      if (cs == null) ic.performContextMenuAction(android.R.id.selectAll);
      return ic.performContextMenuAction(android.R.id.shareText);
    }
    return false;
  }

  private boolean hookKeyboard(int code, int mask) { // ????????????
    final @Nullable InputConnection ic = getCurrentInputConnection();
    if (ic == null) return false;
    if (mask == KeyEvent.META_CTRL_ON) {

      if (VERSION.SDK_INT >= VERSION_CODES.M) {
        if (getPrefs().getKeyboard().getHookCtrlZY()) {
          switch (code) {
            case KeyEvent.KEYCODE_Y:
              return ic.performContextMenuAction(android.R.id.redo);
            case KeyEvent.KEYCODE_Z:
              return ic.performContextMenuAction(android.R.id.undo);
          }
        }
      }
      switch (code) {
        case KeyEvent.KEYCODE_A:
          if (getPrefs().getKeyboard().getHookCtrlA())
            return ic.performContextMenuAction(android.R.id.selectAll);
          return false;
        case KeyEvent.KEYCODE_X:
          if (getPrefs().getKeyboard().getHookCtrlCV()) {
            ExtractedTextRequest etr = new ExtractedTextRequest();
            etr.token = 0;
            ExtractedText et = ic.getExtractedText(etr, 0);
            if (et != null) {
              if (et.selectionEnd - et.selectionStart > 0)
                return ic.performContextMenuAction(android.R.id.cut);
            }
          }
          Timber.i("hookKeyboard cut fail");
          return false;
        case KeyEvent.KEYCODE_C:
          if (getPrefs().getKeyboard().getHookCtrlCV()) {
            ExtractedTextRequest etr = new ExtractedTextRequest();
            etr.token = 0;
            ExtractedText et = ic.getExtractedText(etr, 0);
            if (et != null) {
              if (et.selectionEnd - et.selectionStart > 0)
                return ic.performContextMenuAction(android.R.id.copy);
            }
          }
          Timber.i("hookKeyboard copy fail");
          return false;
        case KeyEvent.KEYCODE_V:
          if (getPrefs().getKeyboard().getHookCtrlCV()) {
            ExtractedTextRequest etr = new ExtractedTextRequest();
            etr.token = 0;
            ExtractedText et = ic.getExtractedText(etr, 0);
            if (et == null) {
              Timber.d("hookKeyboard paste, et == null, try commitText");
              if (ic.commitText(ShortcutUtils.pasteFromClipboard(this), 1)) {
                return true;
              }
            } else if (ic.performContextMenuAction(android.R.id.paste)) {
              return true;
            }
            Timber.w("hookKeyboard paste fail");
          }
          return false;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
          if (getPrefs().getKeyboard().getHookCtrlLR()) {
            ExtractedTextRequest etr = new ExtractedTextRequest();
            etr.token = 0;
            ExtractedText et = ic.getExtractedText(etr, 0);
            if (et != null) {
              int move_to =
                  StringUtils.INSTANCE.findNextSection(et.text, et.startOffset + et.selectionEnd);
              ic.setSelection(move_to, move_to);
              return true;
            }
            break;
          }
        case KeyEvent.KEYCODE_DPAD_LEFT:
          if (getPrefs().getKeyboard().getHookCtrlLR()) {
            ExtractedTextRequest etr = new ExtractedTextRequest();
            etr.token = 0;
            ExtractedText et = ic.getExtractedText(etr, 0);
            if (et != null) {
              int move_to =
                  StringUtils.INSTANCE.findPrevSection(et.text, et.startOffset + et.selectionStart);
              ic.setSelection(move_to, move_to);
              return true;
            }
            break;
          }
      }
    }
    return false;
  }

  /** ?????????????????????????????????????????????????????????/????????????/??????????????????????????????????????? */
  /*
  private String getActiveText(int type) {
    if (type == 2) return Rime.RimeGetInput(); // ????????????
    String s = Rime.getComposingText(); // ????????????
    if (TextUtils.isEmpty(s)) {
      final InputConnection ic = getCurrentInputConnection();
      CharSequence cs = ic != null ? ic.getSelectedText(0) : null; // ?????????
      if (type == 1 && TextUtils.isEmpty(cs)) cs = lastCommittedText; // ????????????
      if (TextUtils.isEmpty(cs) && ic != null) {
        cs = ic.getTextBeforeCursor(type == 4 ? 1024 : 1, 0); // ????????????
      }
      if (TextUtils.isEmpty(cs) && ic != null) cs = ic.getTextAfterCursor(1024, 0); // ?????????????????????
      if (cs != null) s = cs.toString();
    }
    return s;
  } */

  /** ??????Rime???????????????????????????????????? */
  public int updateComposing() {
    final @Nullable InputConnection ic = getCurrentInputConnection();
    activeEditorInstance.updateComposingText();
    if (ic != null && !isWinFixed()) isCursorUpdated = ic.requestCursorUpdates(1);
    int startNum = 0;
    if (mCandidateRoot != null) {
      if (isPopupWindowEnabled) {
        Timber.d("updateComposing() SymbolKeyboardType=%s", symbolKeyboardType.toString());
        if (symbolKeyboardType != SymbolKeyboardType.NO_KEY
            && symbolKeyboardType != SymbolKeyboardType.CANDIDATE) {
          mComposition.setWindow();
          showCompositionView(false);
          return 0;
        } else {

          startNum = mComposition.setWindow(minPopupSize, minPopupCheckSize, Integer.MAX_VALUE);
          mCandidate.setText(startNum);
          // if isCursorUpdated, showCompositionView will be called in onUpdateCursorAnchorInfo
          // otherwise we need to call it here
          if (!isCursorUpdated) showCompositionView(true);
        }
      } else {
        mCandidate.setText(0);
      }
      mCandidate.setExpectWidth(mainKeyboardView.getWidth());
      // ????????????????????????????????????????????????????????????????????????
      mTabRoot.move(mCandidate.getHighlightLeft(), mCandidate.getHighlightRight());
    }
    if (mainKeyboardView != null) mainKeyboardView.invalidateComposingKeys();
    if (!onEvaluateInputViewShown())
      setCandidatesViewShown(textInputManager.isComposable()); // ????????????????????????????????????

    return startNum;
  }

  public void showDialogAboveInputView(@NonNull final Dialog dialog) {
    final IBinder token = inputRootBinding.inputRoot.getWindowToken();
    final Window window = dialog.getWindow();
    final WindowManager.LayoutParams lp = window.getAttributes();
    lp.token = Objects.requireNonNull(token, "InputRoot token is null.");
    lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
    window.setAttributes(lp);
    window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    dialog.show();
  }

  /**
   * ?????????{@link KeyEvent#KEYCODE_ENTER ?????????}????????????
   *
   * @param keyCode {@link KeyEvent#getKeyCode() ??????}
   * @return ???????????????????????????
   */
  private boolean performEnter(int keyCode) { // ??????
    if (keyCode == KeyEvent.KEYCODE_ENTER) {
      DraftHelper.INSTANCE.onInputEventChanged();
      handleReturnKey();
      return true;
    }
    return false;
  }

  /** ??????PC?????????Esc???????????????????????????????????????????????? */
  private void performEscape() {
    if (isComposing()) textInputManager.onKey(KeyEvent.KEYCODE_ESCAPE, 0);
  }

  private void setNavBarColor() {
    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      try {
        final Window window = getWindow().getWindow();
        @ColorInt final Integer keyboardBackColor = getImeConfig().colors.getColor("back_color");
        if (keyboardBackColor != null) {
          BarUtils.setNavBarColor(window, keyboardBackColor);
        }
      } catch (Exception e) {
        Timber.e(e);
      }
    }
  }

  @Override
  public boolean onEvaluateFullscreenMode() {
    final Configuration config = getResources().getConfiguration();
    if (config != null) {
      if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
        return false;
      } else {
        switch (getPrefs().getKeyboard().getFullscreenMode()) {
          case AUTO_SHOW:
            final EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0) {
              return false;
            }
          case ALWAYS_SHOW:
            return true;
          case NEVER_SHOW:
            return false;
        }
      }
    }
    return false;
  }

  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParameters();
  }

  /** Updates the layout params of the window and input view. */
  private void updateSoftInputWindowLayoutParameters() {
    final Window w = getWindow().getWindow();
    if (w == null) return;
    final View inputRoot = inputRootBinding != null ? inputRootBinding.inputRoot : null;
    if (inputRoot != null) {
      final int layoutHeight =
          isFullscreenMode()
              ? WindowManager.LayoutParams.WRAP_CONTENT
              : WindowManager.LayoutParams.MATCH_PARENT;
      final View inputArea = w.findViewById(android.R.id.inputArea);
      // TODO: ???????????????????????????????????????????????????????????????????????????
      if (isFullscreenMode()) {
        Timber.i("isFullscreenMode");
        /* In Fullscreen mode, when layout contains transparent color,
         * the background under input area will disturb users' typing,
         * so set the input area as light pink */
        inputArea.setBackgroundColor(parseColor("#ff660000"));
      } else {
        Timber.i("NotFullscreenMode");
        /* Otherwise, set it as light gray to avoid potential issue */
        inputArea.setBackgroundColor(parseColor("#dddddddd"));
      }

      ViewUtils.updateLayoutHeightOf(inputArea, layoutHeight);
      ViewUtils.updateLayoutGravityOf(inputArea, Gravity.BOTTOM);
      ViewUtils.updateLayoutHeightOf(inputRoot, layoutHeight);
    }
  }

  public boolean addEventListener(@NonNull EventListener listener) {
    return eventListeners.add(listener);
  }

  public boolean removeEventListener(@NonNull EventListener listener) {
    return eventListeners.remove(listener);
  }

  public interface EventListener {
    default void onCreate() {}

    default void onInitializeInputUi(@NonNull InputRootBinding uiBinding) {}

    default void onDestroy() {}

    default void onStartInputView(@NonNull EditorInstance instance, boolean restarting) {}

    default void osFinishInputView(boolean finishingInput) {}

    default void onWindowShown() {}

    default void onWindowHidden() {}

    default void onUpdateSelection() {}
  }

  private boolean candidateExPage = false;

  public boolean hasCandidateExPage() {
    return candidateExPage;
  }

  public void setCandidateExPage(boolean candidateExPage) {
    this.candidateExPage = candidateExPage;
  }
}
