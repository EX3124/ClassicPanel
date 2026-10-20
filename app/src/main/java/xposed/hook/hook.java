package xposed.hook;

import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Restores the classic Flyme control-centre look on the integrated shade
 * (settings system notification_and_control_center == 1):
 *   - white blurred cover instead of the fixed lock-screen wallpaper
 *   - opaque white cards
 *   - dark clock / date / status-bar icons
 *
 * <p>The panel is theme-aware.  Everything the module paints has a night counterpart taken from the
 * vendor's own dark palette ({@code qs_tile_container_bg_color_for_night} and friends), so with the
 * system in dark mode the classic panel is the dark plate + near-black cards + white ink that the
 * old design used, instead of a white plate with white text on it.  See {@link #sNightUi}.
 * Hook points
 *   1  ResourcesImpl#getValue                                    colour overrides
 *      (+ the portrait page shift and the "收纳箱 / 不重要通知" panel shift, both derived
 *       from the live APK dimens: putPanelShiftDimens / putFilterPanelShiftDimens)
 *   2  LockscreenWallpaperManager#setNotificationShadeWindowView cover injection
 *   4  StatusBarHeaderView#updateViewColor                       dark clock / date / title
 *      (+ RelativeLayout#onLayout                                 the clock row's shift, and the
 *      header growth that also positions the LOCK SCREEN's own control centre -- see
 *      shiftHeaderRow)
 *   5  QSStatusBarController#updateViewColor                     dark centre status bar
 *   5b TintedIconManager#setTint                                 dark status icons
 *   6  MzQSPanel#onFinishInflate / #updateResources               the two control-centre cards
 *      ({@code connectivity_tiles_wrapper} and {@code slider_wrapper}) take the shared card
 *      surface -- {@link #applyQsCardFill}, driven per frame by {@link #repaintQsCards} for every
 *      panel that inflates them: the classic shade AND the lock screen's independent control
 *      centre ({@code control_center_view}) are two layouts, both of which inflate an MzQSPanel
 *   7  NotificationBackgroundView#onDraw / #setBlurBackground /
 *      #setDrawableAlpha / #updateLiveBlurAlpha / #setRadius,
 *      ActivatableNotificationView#setBackground                 opaque white notification cards
 *   7m MediaCarouseTransitionLayout#setBackground / #setBlurBgForLive / #setBlurBgForStatic
 *      (+ its id/media_mask_bg artwork layer)                     the media player card, same
 *      opaque white as a notification row (its own surface is built outside the row view classes)
 *   8  NotificationFilterPanel#updateColors / #updateHeaderMarginTop /
 *      #onFinishInflate / #onConfigurationChanged                 dark colours in the
 *      "intercepted notifications" box (the inbox button in the shade header)
 *   8b CornerAlphaOptimizedImageButton#changeFilterColor          dark close glyph of that
 *      box (its own recolour funnel writes white after every other pass)
 *  10 FlymeOngoingPrivacyChip#updateColors / #onFinishInflate /
 *      #setPrivacyList / #updateView / #dispatchDraw              dark "相机正在使用相机" chip
 *      (the ongoing-privacy chip in the shade's top left; see hookPrivacyChip)
 *  11 NotificationPanelViewController#updatePadding               the chip's row: landscape it is
 *      put flush with the notification column's left edge (see hookPrivacyChipRow); the vertical half
 *      is the dimen override in putPrivacyChipRowDimens
 *  13 EmptyShadeView#updateColor / #onFinishInflate
 *      dark "暂无通知" hint (empty_shade_text) on the white plate
 *   (+ NotificationPanelViewController ctor / ShadeInteractor are only read, not hooked)
 */
public class hook implements IXposedHookLoadPackage, IXposedHookInitPackageResources {

    private static final String TAG = "CCRestore: ";
    private static final String SYSUI = "com.android.systemui";

    /* ---------------------------- tunables ---------------------------- */

    /** White "blur mask" foreground painted over the blurred wallpaper (ARGB). */
    private static final int COVER_COLOR = 0xE4F6F8FB;
    /** Mask over the blurred wallpaper; transparent = show the blur itself (mode 0's look). */
    /** Experiment: put the white mask on lock_wallpaper_mirror instead of our own scrim.
     *  It is a SurfaceView owned by the platform, so the mask may be reset on release;
     *  set to false to go straight back to the module-owned scrim. */
    private static final boolean MASK_ON_MIRROR = false;  // proven twice: the platform re-asserts the mirror on release (#090c0c)
    /**
     * Background of every card on the classic panel: the notification cards, the media player card,
     * and -- since the control centre's cards and tiles were moved onto it -- the two
     * control-centre cards ({@code connectivity_tiles_wrapper} / {@code slider_wrapper}) and the
     * quick-toggle grid.  Renamed from {@code NOTIFICATION_BG_COLOR}, which stopped describing it
     * once the tiles took the same value.
     *
     * <p>Why the notification row has to be forced at all: {@code ActivatableNotificationView.setBackground()}
     * hands the vendor's frosted-glass colour ({@code 0xB2FFFFFF}, i.e. 70% white) to
     * {@code NotificationBackgroundView.setBlurBackground()}, which passes it to
     * {@code MzBlurUtils.setBackgroundBlurDrawable()} as the drawable's <em>colour</em> and then
     * calls {@code setBackgroundBlurDrawableAlpha()} on it.  The blur only goes to alpha 0 when
     * {@code CenterController.getPanelAlpha() == 255} <em>and</em> the panel is fully expanded, so
     * during the drag -- and for notifications that arrive while it is open -- the row is a 70%
     * veil over the blurred wallpaper.  Against the old design's white cover that reads as "the
     * card is only blurred, it is not lying on the control-centre background", which is exactly
     * what the classic look must not do.
     *
     * <p>The alpha is calibrated against the reference device (Flyme 9): its light row measured 86.7%
     * white over a magenta probe backdrop, i.e. it lets 13.3% of what is behind it through.
     * Measured the same way on the Flyme 12 device (work/card_alpha_findings.md), {@code 0xE3}
     * reproduced that exactly (86.8%).  The value in hand is deliberately lower than that
     * calibration -- see the constant -- so treat the measurement as the reference for the
     * relationship, not as the current alpha.
     *
     * <p>Translucent on purpose -- it is what lets the backdrop blur through the card -- so the card
     * has to erase what the vendor painted under it before it paints: see {@link ClassicCardFill}.
     *
     * <p>This is also the fill of the <em>media player card</em> (the media carousel between the
     * control centre and the notifications).  That card is not a notification row and builds its
     * surface in its own class, so it used to keep the vendor's {@code 0xB2FFFFFF} frosted layer
     * and read as a grey plate next to the white rows -- see {@link #applyMediaCardTint}.
     */
    private static final int CARD_BG_COLOR = 0xDDFFFFFF;
    /**
     * The white mask -- the third layer of the classic backdrop, and deliberately translucent.
     *
     * <p>The stack the classic panel is built from, bottom to top:
     * <ol>
     *   <li>the app (or whatever is behind the shade) -- {@code hideWallpaperLayers()} clears the
     *       vendor's wallpaper stack so this layer is the app rather than the wallpaper;</li>
     *   <li>the cover's blur drawable -- {@code MzBlurUtils.setBackgroundBlurDrawable()}, whose
     *       radius follows the drag ({@code COVER_BLUR_RATIO});</li>
     *   <li>this white mask, painted over the blur by our own scrim view;</li>
     *   <li>the control-centre controls and the notification cards.</li>
     * </ol>
     *
     * <p>The mask must stay see-through: at 0xFF the blur below it is erased and the panel becomes
     * a flat white plate, which loses the "blurred interface underneath" the classic look is made
     * of.  Measured on the device over a light backdrop: 0xE0 reads 213/255, 0xFF pure white only
     * reaches 242 -- so an opaque mask buys 30 levels at the cost of the whole layer stack.
     */
    private static final int COVER_SCRIM_COLOR = 0xE0F2F2F2;
    /**
     * Force the old design's dark header (clock / date / lunar / collapse button and the centre
     * status bar) instead of letting the system pick it.
     *
     * <p>On this build the choice is not "light or dark for the wallpaper": classic mode always
     * takes {@code StatusBarHeaderView.onDarkChanged}'s {@code mIsClassicalPanel} branch, which
     * paints {@code mKeyguardColor} -- the colour {@code AutoColorUtil} derived from the lock
     * wallpaper.  With the module's white veil behind the header that auto-colour can come out
     * light, leaving a white clock on a white veil.  The veil is always light while the classic
     * shade is open, so the readable (and the old design's) choice is always dark.
     */
    private static final boolean FORCE_DARK_HEADER = true;
    /** Blur radius for the cover's wallpaper-blur drawable. */
    private static final float COVER_BLUR_RADIUS = 0.6f;
    /**
     * Fraction of {@code max_window_blur_radius} used for the backdrop blur -- the knob for
     * "how heavy the blur looks".  The radius is {@code ratio x this x max_window_blur_radius},
     * where {@code ratio} is the RAW drag run through {@link #COVER_BLUR_FULL_AT}, on purpose
     * <em>not</em> the mask alpha.
     *
     * <p>Both used to be the same number ({@code a}), so the moment the white mask got its own ramp
     * the blur inherited it and the backdrop came out far too heavily blurred.  Measured with
     * SurfaceFlinger (work/sf_watch.ps1), this {@code COVER_BLUR_RATIO} (0.2 x 382 = 76px) is the one
     * the user actually sees, because it is the radius of the blur region the cover's
     * {@code BackgroundBlurDrawable} registers on the shade window; the window's own radius
     * ({@link #CLASSIC_SHADE_BLUR_MAX_RATIO}, 19px) rides along underneath it.
     */
    private static final float COVER_BLUR_RATIO = 0.2f;    // visible layer: 0.2 x max_window_blur_radius, ramped
    /**
     * Fraction of the pull at which the backdrop blur has reached its full radius: the blur ramps
     * with the RAW drag from the very first pixel and is done at a fifth of a pull (639px here).
     *
     * <p>Why a ramp at all (measured, see work/blur_ramp_fix.md).  The blur that is visible on this
     * build is the shade window's blur-of-behind, and the strongest contributor to it is the
     * {@code BackgroundBlurDrawable} on the module's cover: it carries the full
     * {@code COVER_BLUR_RATIO} radius from the moment the cover is visible.  The cover used to be
     * shown only once the white mask had started ({@code a > 0.005}, i.e. 262px of travel), so the
     * backdrop went from "almost no blur" to a full-radius blur inside one frame -- the jump the user
     * sees right after the first 240px of a pull-down.  Ramping the radius with the drag removes the
     * step, and starting the cover (and therefore its blur region) on the first frame of the pull
     * keeps the blur growing from 0 instead of appearing.
     *
     * <p>0.2 because the blur has to be well up by the time the white veil starts (1% of a pull,
     * 32px): at 240px of travel the radius is already 76 x 0.073/0.2 = 28px, so the backdrop is
     * visibly blurred before the veil arrives, and from 639px on it is the same full radius it always
     * had.  The veil runs faster (full at 223px, {@link #COVER_MASK_FULL_AT}), so the white is up
     * first and the blur keeps sharpening underneath it.
     */
    private static final float COVER_BLUR_FULL_AT = 0.2f;
    /**
     * Alpha the cover keeps while the pull-down is in progress, even though the white mask is not due
     * yet ({@link #COVER_MIN_EXPANSION}, 1% of a pull = 32px).
     *
     * <p>SurfaceFlinger reports the cover's blur region the whole time (the {@code blurRegions} entry
     * is there from the first frames, with the ramped radius), but a layer that is <em>completely</em>
     * transparent has nothing for that blur to be composited through: measured over the first 250px
     * of a pull, the backdrop stayed as sharp as with the blur off, and then snapped to the region's
     * radius in the frame where the mask alpha first left 0.  1% alpha is 2/255 of white over the
     * backdrop -- invisible -- and it keeps the blur layer alive from the first pixel of the drag, so
     * the radius ramp is what the user sees instead of a step.
     */
    private static final float COVER_MIN_ALPHA = 0.01f;
    /**
     * Strength of the blur layer (MzBlurUtils.setBackgroundBlurDrawableAlpha).  The blur radius
     * below ~0.2 of max_window_blur_radius has no visible effect -- the platform clamps it -- so
     * this is the knob for going lighter.
     */
    private static final float COVER_BLUR_ALPHA = 1.0f;
    /** Dark text/icon tint used on the light cover. */
    private static final int DARK_TINT = 0xFF000000;
    /**
     * White ink of the old design's dark panel.  Used where the module has to write the colour itself
     * and the vendor's automatic colour is stale or wrong: the intercepted-notification box's title,
     * hint and close glyph in dark mode (their own recolour pass only runs on {@code updateColors} and
     * rotation, so a theme switch used to leave them at the black the light plate needed).
     */
    private static final int LIGHT_TINT = 0xFFFFFFFF;
    /** experiment: false = let the system choose the header colour */
    private static final boolean sForceDark = false;
    /** Opaque card fill (old Flyme: #ffffff). */
    private static final int CARD_COLOR = 0xFFFFFFFF;
    /** Slider track inside the white slider card (old Flyme: #ededed). */
    private static final int SLIDER_TRACK_COLOR = 0xE0DDDDDD;
    /** Header text colour (old Flyme: #232323). */
    private static final int HEADER_TEXT_COLOR = 0xFF232323;

    /* ------------------------- the night palette ------------------------- */

    /*
     * The classic panel changes model in dark mode.  Every value below was calibrated against the
     * reference device (Flyme 9 / MEIZU 18, captures in work/theme/tgt-dark-*.png, measurements in
     * work/theme/README-night.md) and the vendor's own dark palette in this APK (work/new_res.txt):
     *
     *   backdrop               #45484d  the reference's backdrop is the wallpaper's blur (it has no
     *                                   plate at all); here the vendor's own night scrim darkens the
     *                                   backdrop by 63% (`color/blur_background_cover_nightmode`), so
     *                                   the plate is calibrated to land on the same #43..#45.
     *   tile / connectivity    #80000000  qs_tile_container_bg_color_for_night (50% black)
     *   slider card            #80000000  same surface as the tile card (measured on the reference)
     *   slider track           #12ffffff  7% white -> card + ~16/255, the reference's relationship
     *   notification card      (no fill: the vendor's own night row is used -- 去掉通知卡片染色)
     *   ink                    (nothing forced: the vendor's automatic colour owns the clock, the
     *                           centre status bar and the 收纳箱 box in dark mode)
     *
     * The lightness relationships are what make the dark panel read like the reference: the cards are
     * half-black over the backdrop (so they are clearly darker than it).  In dark mode the module
     * paints as little as possible: no plate, no row fill, no forced ink.
     */

    /** Night card fill: {@code qs_tile_container_bg_color_for_night} (50% black). */
    private static final int CARD_COLOR_NIGHT = 0x80000000;
    /** Night header text colour (the reference's night {@code qs_header_text_color} is #d9ffffff). */
    private static final int HEADER_TEXT_COLOR_NIGHT = 0xFFFFFFFF;
    /**
     * Night cover scrim: none at all -- "去掉白遮罩".
     *
     * <p>On the reference device dark mode has no white veil: the panel backdrop is simply the
     * wallpaper's blur, darkened by the system's night mode (measured behind the cards: #43454d ..
     * #454c54).  So in dark mode the cover keeps only its blur drawable and nothing is painted over
     * the backdrop -- whatever the vendor's own layers produce is what the panel shows, exactly like
     * the reference.  (Light mode keeps the opaque plate; that is what the classic light look is.)
     */
    private static final int COVER_SCRIM_COLOR_NIGHT = 0x00000000;
    /** Night notification card fill: the reference's row colour over that backdrop (#22..#23). */
    private static final int CARD_BG_COLOR_NIGHT = 0xF7212124;
    /**
     * Night slider track: 7% white, i.e. the track reads {@code card + ~16/255}, which is the exact
     * relationship measured on the reference device (card #222327, track #323235).  The vendor's own
     * night track ({@code qs_slider_bg_for_night}, 35% black) lands on the opposite side of the card
     * there, so it is overridden by name as well -- see {@link #putColorNight}.
     */
    private static final int SLIDER_TRACK_COLOR_NIGHT = 0x12FFFFFF;
    /** Night slider track while pressed: the same white, twice as strong. */
    private static final int SLIDER_TRACK_PRESS_COLOR_NIGHT = 0x26FFFFFF;
    /**
     * Text colour of the "intercepted notifications" box (the inbox button in the shade header and
     * the panel it opens, 收纳箱 / "不重要通知").  Flyme hard-codes white there and its own colour pass
     * ({@code NotificationFilterPanel#updateColors}) re-applies white whenever
     * {@code KeyguardUpdateMonitor.usedDefaultColor()} is true -- which it is on this build because
     * the platform reads the lock-screen wallpaper, not our white cover.  Same root cause as hook 4,
     * so the same dark ink as the header is forced here as well.
     */
    private static final int FILTER_PANEL_TEXT_COLOR = HEADER_TEXT_COLOR;
    /** Notification card fill (old Flyme: near white over the blurred wallpaper). */
    /**
     * Shade expansion below which the cover/white mask stays completely off -- and, because the
     * mask ramp is a straight line from here to {@link #COVER_MASK_FULL_AT}, also the start of that
     * ramp.  0.01 of the shade window is 32px on this device (3192px tall), i.e. the first ~1% of a
     * full pull shows no white at all.
     */
    private static final float COVER_MIN_EXPANSION = 0.069f;
    /**
     * Expansion at which the white mask reaches full strength.  The ramp is the straight line
     * {@code (drive - COVER_MIN_EXPANSION) / (COVER_MASK_FULL_AT - COVER_MIN_EXPANSION)}: invisible
     * below 1% of a full pull (32px), full white at 7% (223px here), 1 after that.  With the blur
     * following its own (slower) ramp to 20%, the white is therefore fully up well before the blur
     * finishes: the pull reads as "white plate with the backdrop still sharpening underneath".
     *
     * <p>Why the raw expansion cannot be the alpha: {@code getExpandedFraction()} is
     * {@code mExpandedHeight / getMaxPanelHeight()}, and on this build that denominator is the whole
     * shade window (3192px) while the panel only comes to rest at 1386px (0.43) -- a plain
     * {@code alpha = fraction} would leave the veil at 43% when the panel has already settled.
     */
    private static final float COVER_MASK_FULL_AT = 0.0698f;
    /** Landscape shade header metrics taken from the old Flyme 9.2 header. */
    private static final float LS_CLOCK_DP = 35f;
    private static final float LS_DATE_DP = 10f;
    private static final float LS_TITLE_DP = 27f;
    private static final float LS_LUNAR_TOP_MARGIN_DP = 2f;
    private static final float LS_DATE_TOP_MARGIN_DP = 4f;
    /** Old content top edge in landscape (stock put it at 72-76dp, i.e. almost touching). */
    private static final float LS_NOTIFICATION_TOP_PADDING_DP = 97f;
    /**
     * Portrait: fine-tuning of the header row on top of the page shift, i.e. the row ends up
     * {@code LS_HEADER_ROW_SHIFT_DP + BELOW_CLOCK_SHIFT_DP} below stock.
     */
    private static final float LS_HEADER_ROW_SHIFT_DP = 8f;
    /** Landscape: the same time/date row sits this much lower than stock (2dp + 4dp - 2dp). */
    private static final float LS_HEADER_ROW_SHIFT_LAND_DP = 4f;
    /** Card height for both the connectivity and the brightness/volume card. */
    private static final float LS_CARD_HEIGHT_DP = 196f;
    /**
     * The classic QS region height and the notification column's start height are BOTH this dimen:
     * {@code MzQSImpl.updateResource()} does
     * {@code mQsMinExpansionHeight = getDimensionPixelOffset(qs_min_expansion_height)}, which
     * {@code QuickSettingsControllerImpl} turns into mMinExpansionHeight and
     * {@code calculateNotificationsTopPadding} returns it in portrait.  The effective value here is
     * 336dp (values-sw343dp; the base is 348dp) and the region starts 150dp down, leaving only 186dp
     * for the cards.  Raising the dimen grows the region AND moves the notification start by the
     * same amount -- so the taller cards fit without switching clipChildren off, which is what used
     * to let the tile grid below them bleed over the notifications.
     */
    private static final float LS_QS_MIN_EXPANSION_DP = 336f;
    /** Region height the stock dimen leaves (336dp minus the 150dp the region starts at). */
    private static final float LS_QS_REGION_BASE_DP = 186f;
    /**
     * Gap between the card's bottom edge and the notification column, measured on the old design:
     * its card ended at 336.0dp and its first notification card began at 360.3dp, i.e. 24dp.  This
     * also becomes the empty band under the cards inside the region.
     */
    private static final float LS_REGION_SLACK_DP = 18f;
    private static final float LS_STATUS_BAR_TOP_DP = 10f;
    private static final float LS_STATUS_BAR_END_MARGIN_DP = 68f;
    /** Slider track width as a fraction of the slider column (old: 49dp of 153.7dp). */
    private static final float LS_SLIDER_TRACK_RATIO = 49f / 153.7f;
    /** Old slider: track 49dp on a 179.7dp tall card with 18dp top/bottom inset, radius 9dp. */
    private static final float LS_SLIDER_V_INSET_RATIO = 18f / 179.7f;
    /** Trimmed off the top AND bottom inset of both tracks (the track grows by twice this). */
    private static final float LS_SLIDER_V_INSET_TRIM_DP = 2f;
    private static final float LS_SLIDER_RADIUS_RATIO = 9f / 49f;
    /**
     * Icon sizes of the two sliders.  Both inflate a 24dp icon view, which renders an 18.6dp
     * speaker and a 20.0dp sun, while the old design's were 12.7dp and 15.3dp respectively -- so
     * 16.5dp and 18.4dp of view give those glyphs back.
     */
    private static final float LS_VOLUME_ICON_DP = 18.5f;
    private static final float LS_BRIGHTNESS_ICON_DP = 20.4f;

    /* ---------------- the "相机正在使用相机" chip's row ---------------- */

    /**
     * Portrait: how far the ongoing-privacy chip has to move <b>up</b> to sit on the status bar's own
     * line.
     *
     * <p>Measured on the device (1368x3192, screenshots): the status bar's glyphs ("4G 100") occupy
     * rows 153..186, i.e. a centre of 169.5px, while the chip's icon (183..231) and label (190..225)
     * are centred on 207 -- 37.5px lower, which is what the user sees as the chip hanging under the
     * status bar instead of being level with it.  37.5px at this device's 3.5 density is 10.7dp, and
     * the value is kept in dp so another density lands on the same line.
     */
    private static final float CHIP_ROW_UP_PORTRAIT_DP = 10.8f;
    /**
     * Landscape: how far the same chip has to move <b>down</b> onto the status bar's line.
     *
     * <p>Measured on the device (3192x1368, both rotations): the status bar's glyphs occupy rows
     * 83..116 (centre 99.5px) while the chip is centred on 74 -- 25.5px too high, because
     * {@code updatePadding} drops the group's 38dp top padding in landscape, so the chip starts
     * higher there than in portrait.  Published for the log line only: {@link #alignPrivacyChipRow}
     * measures the offset from the two rows themselves rather than applying this number, because the
     * wrapper padding that carries it in landscape is read once per inflation and cannot be moved by
     * a dimen (see {@link #putPrivacyChipRowDimens}).
     */
    private static final float CHIP_ROW_DOWN_LAND_DP = 7.3f;
    /** Stock value of the portrait dimen {@link #putPrivacyChipRowDimens} rewrites (no config variants). */
    private static final float CHIP_GROUP_TOP_STOCK_DP = 38f;
    /**
     * Landscape: the chip's container is flush with the notification column's left edge, i.e. the
     * width of the {@code ongoing_chip_wrapper} start padding that the notification list's own inset
     * cancels out (both are {@code ongoing_chip_wrapper_padding} / the list's 20dp content margin).
     *
     * <p>Nothing to tune here: the value is measured live from {@code notification_stack_scroller}
     * (see {@link #alignPrivacyChipRow}), because the offset the vendor writes is the display-cutout
     * inset, which is 130px in one landscape rotation and 0 in the other.
     */
    private static volatile int idNotificationScroller = 0;
    /** Guards hook 11's one-shot log lines. */
    private static volatile boolean sChipRowLogged = false;

    /* ---------------- the "收纳箱 / 不重要通知" panel (portrait) ---------------- */

    /**
     * Portrait: how far the whole intercepted-notification panel is pushed down, so that its title
     * "不重要通知" sits on the same line as the shade's clock instead of above it.
     *
     * <p>The panel is a sibling of the shade inside {@code notification_container_parent}
     * ({@code status_bar_expanded.xml} includes {@code notification_filter_panel.xml}), and it places
     * itself from two classic dimens, both read by
     * {@code NotificationFilterPanel#updateHeaderMarginTop()}:
     * <ul>
     *   <li>{@code classic_notification_filter_header_margin_top} -- the title row's top margin
     *       (34dp stock, i.e. 119px here); with the title's own
     *       {@code classic_notification_filter_header_top} (36dp) on top of it that puts the
     *       {@code notification_filter_header} text at 286px on this device, while the clock
     *       ({@code time_container}, 32dp lower too because of {@link #BELOW_CLOCK_SHIFT_DP}) is at
     *       446px;</li>
     *   <li>{@code classic_notification_filter_panel_stack_margin_top} -- the panel's notification
     *       list top (127dp stock, 445px here, i.e. exactly the stock bottom edge of the title row).
     *       Raising it by the same amount as the header keeps the list one row below the title and
     *       shifts the cards with it; the list is {@code match_parent} high, so it simply ends lower
     *       -- nothing overflows the panel.</li>
     * </ul>
     *
     * <p>Measured on the device (1368x3192 portrait, screenshot pixels -- what the user sees):
     * <pre>
     *   clock (time_container) ink           450 - 559      = 173.8dp
     *   panel title ink, stock margins       286 - 388      (container top 119px = 34dp)
     *   panel title ink, this value          452 - 554      (container top 285px = 81.4dp)
     * </pre>
     * so the row travels 143px, and both dimens are raised by that same amount.  Because the title
     * row's own top is derived from {@code classic_notification_filter_header_margin_top} plus
     * {@code classic_notification_filter_header_top} (36dp), the margin needs the full distance:
     * {@code classic_notification_filter_panel_stack_margin_top} (445px stock) therefore lands on 588
     * and the notification list ends up exactly where the title row's footprint used to be -- the
     * cards keep one row of clearance under the title in every state, and the list is
     * {@code match_parent} high so it simply ends lower; nothing overflows the panel.
     *
     * <p><b>Expressed in dp, adapted by density.</b>  160px is 160/3.5 = 45.7dp at this device's
     * {@code Configuration.density}, rounded to the whole <b>46dp</b> below (the panel's own ruler is
     * coarser than this: measured across runs the same value lands anywhere in a +/-6px band, so the
     * fraction buys nothing).  {@link #dpToWrittenPx} turns it into the resource value, and on another
     * density the same dp is written instead, so the title keeps sitting on the clock's line rather
     * than on a fixed pixel row.  The pixel figures above are this device's instance of it.
     *
     * <p>Do NOT read the filter panel's uiautomator bounds as a screen criterion: for this panel they
     * come out 41px above the glyphs the screenshot shows (the clock's bounds are 27px above its own
     * glyphs), which is why the value above was bisected against screenshots
     * (work/bisect_ink.py, work/verify_final.py).
     *
     * <p>Only the classic pair is overridden: {@code isUseClassicsStyle()} is true on this build, and
     * the live value the helper reads keeps the shift right if that ever changes.
     *
     * <p>Portrait only, deliberately.  Landscape places its title row from the same two dimens but
     * the stock values already put it on the clock's line (measured, 1596x1368: title row top 105px,
     * control-centre clock top 119px) and its list needs a smaller correction of its own; this shift
     * is calibrated against the portrait clock's position, so {@code addPortDimenShift} writes it into
     * the portrait map alone and {@link #FILTER_PANEL_SHIFT_LAND_DP} handles landscape.
     */
    private static final float FILTER_PANEL_SHIFT_DP = 46f;
    /** The two live-classic values {@link #putFilterPanelShiftDimens} derives from. */
    private static final float FILTER_HEADER_MARGIN_TOP_STOCK_DP = 34f;
    private static final float FILTER_STACK_MARGIN_TOP_STOCK_DP = 127f;

    /**
     * Landscape: how far the panel's notification list is pushed down.
     *
     * <p>Landscape needs a different treatment from portrait.  There the title row and the clock are
     * already on the same line with the stock margins (1596x1368: title row top 105px = 30dp, the
     * row 147px tall = {@code notification_filter_header_height} 42dp, control-centre clock top
     * 119px), so only the list moves -- stock it starts at 252px (72dp), flush under the title row's
     * bottom edge, which is what puts the first card too high.  With the title row ending at 252px and
     * the status bar above it, a top of ~340px (97dp) gives the list the same kind of clearance the
     * portrait panel has: 88px = 25.14dp of travel, rounded to 25dp (0.5px of slack, well inside the
     * ruler on this panel -- see the note in {@link #FILTER_PANEL_SHIFT_DP}).
     *
     * <p>Writes {@code classic_notification_filter_panel_stack_margin_top} for landscape only (72dp
     * stock, live-read so this stays an offset).  The same 1:1 dp -> written-px conversion as portrait
     * applies ({@link #dpToWrittenPx}), so 25dp writes 88 and the list lands on ~340px.
     */
    private static final float FILTER_PANEL_SHIFT_LAND_DP = 25f;
    /** Landscape stock value of the dimen above. */
    private static final float FILTER_STACK_MARGIN_TOP_LAND_STOCK_DP = 72f;

    /**
     * Landscape: font size of the panel's title "不重要通知", so it matches the control centre's own
     * title "控制中心" in the pane next to it.
     *
     * <p>The two come from different resources.  {@code NotificationFilterPanel#updateResources()}
     * sets its title to {@code classic_notification_panel_title_size} while {@code isUseClassicsStyle()}
     * is true -- 17dp in {@code values-land} -- whereas the control centre's title uses the same
     * {@code notification_panel_title_size} as the notification centre's: 28dp in landscape (32dp in
     * portrait), which is also what the panel picks when classic style is off.  Measured on the
     * device (1596x1368): the filter glyphs are ~61px tall and the control centre's ~100px.
     *
     * <p>So the classic dimen is overridden for landscape only, with the value the non-classic path
     * would have used.  It is the filter panel's own dimen ({@code classic_notification_panel_title_size}
     * has exactly one reader in the APK, that line in {@code updateResources}), so nothing else moves.
     * Portrait keeps the stock 32dp: there it is the control-centre title's sibling resource
     * ({@code notification_panel_title_size}) that also reads 32dp, so both are already equal.
     */
    private static final float FILTER_TITLE_SIZE_LAND_DP = 28f;

    /**
     * Portrait: how far the tile-edit page's first row is pushed down, so that "长按以编辑" sits on the
     * shade clock's line instead of above it.
     *
     * <p>Tile editing swaps two children of the same {@code StatusBarHeaderView}
     * ({@code mz_status_bar_header.xml}): the clock row ({@code time_container}) is hidden and the edit
     * row ({@code edit_container}: "长按以编辑" + the "显示开关名称" checkbox + the done and inbox
     * buttons) is shown.  They are siblings, both placed by the header's top padding, but the edit row
     * is <em>not</em> the clock's height: the clock row is {@code qs_time_container_height} (44dp) plus
     * the header's own bottom slack, while the edit row just wraps its two lines.  Measured on the
     * device (1368x3192): with the control centre open the clock row top is 420px and the edit row top
     * is 280px, i.e. the edit row starts 140px too high and its label's ink (296-360) sits well above
     * the clock's (446-554).
     *
     * <p>One shift fixes both the row and its text: the edit row is given this much top margin, which
     * also moves it inside the header's bounds (595px tall, so 280 + 161 = 441 still leaves room for
     * the row's 193px).  The tile grid is a different container ({@code mz_quick_settings_container},
     * top 637px) and does not move -- only the header's edit row does.
     *
     * <p>Same dp rule as the filter panel: 161px of travel at this device's 3.5 density is 46dp, and
     * {@link #dpToWrittenPx} turns it into the value the layout actually uses.
     */
    private static final float EDIT_HEADER_SHIFT_DP = 46f;

    /**
     * The floating brightness bar (the large slider SystemUI lifts out while the control-centre
     * brightness slider is held) is positioned by
     * {@code BrightnessMirrorController#setLocationAndSize()} from
     * {@code View#getLocationInWindow()} -- and on this build it lands 66px left of the slider it is
     * supposed to cover, i.e. not aligned with the control centre's bar.
     *
     * <p>Measured with the bar up (uiautomator, portrait 1368x3192):
     * <pre>
     *   control-centre brightness slider   [779,699][967,1261]   centre x 873  (held: [784,713][962,1247])
     *   mirror container, stock            [708,637][896,1199]   centre x 802  -> 66px left of the slider
     *   mirror container, +66px (25.5dp)    [797,637][985,1199]  centre x 891  -> 18px past it
     *   mirror container, +47px (20.4dp)    [778,637][966,1199]  centre x 872  -> on the slider
     * </pre>
     * The vertical alignment is already right (the container carries the same 5px padding the mirror
     * lays out with), so this correction is x only, and it is applied <em>on top of</em> whatever
     * translation the vendor wrote, so that placement still happens: only the residual error is
     * removed.  The value is the dp the bar has to move right; the actual pixel is re-measured at
     * pre-draw time, so it stays right if the panel moves.
     */
    private static final float BRIGHTNESS_MIRROR_OFFSET_DP = 20.4f;
    /**
     * Vertical counterpart of {@link #BRIGHTNESS_MIRROR_OFFSET_DP}: the bar's container sits this
     * much too high (637 vs the slider's 699).  Applied to the container, not the card: the card has
     * to stay inside the container, whose clip otherwise cuts the bar short (measured 188x236 instead
     * of 188x562).  62px at this device's density is 17.7dp.
     */
    private static final float BRIGHTNESS_MIRROR_OFFSET_Y_DP = 17.7f;
    /**
     * Corner radius factor for the bar's card -- see {@link #applyMirrorCorner}.
     *
     * <p>{@code qs_corner_radius} is 18dp (63px here), which is the radius of the control-centre
     * <em>wrapper's</em> card.  Drawn on the bar's own, narrower card that same number reads as a pill
     * (measured inset curve ~75px), so it is scaled down to the value fitted from the device
     * screenshots (~30px inset on the control centre's card): 30/63 = 0.48.
     */
    private static final float BRIGHTNESS_MIRROR_CORNER_RATIO = 0.48f;
    /** Guards {@link #hookBrightnessMirrorAlign}'s one-shot log line. */
    private static volatile boolean sMirrorLogged = false;

    /* --------------- backdrop blur while the brightness mirror page is up --------------- */

    /**
     * Backdrop blur radius (px) while the brightness mirror page is up: the "亮度调整页" the user
     * reaches by holding the control-centre brightness slider and moving the finger vertically.
     *
     * <p>That page is <em>not</em> a pull-down.  {@code BrightnessMirrorController#showMirror()}
     * (decompiled, {@code statusbar/policy/BrightnessMirrorController.java:54}) lifts the big slider
     * out of the panel, calls {@code mNotificationPanel.setAlpha(0, true)} (or
     * {@code CenterController#setPanelAlpha(0)}) and
     * {@code NotificationShadeDepthController#setBrightnessMirrorVisible(true)}; the panel and
     * everything inside it -- including this module's cover and its white scrim -- is therefore
     * invisible and the desktop shows through the shade window.
     *
     * <p>What does <em>not</em> go away is the cover's {@code BackgroundBlurDrawable}: it is still
     * drawn (alpha 0 is a property of the panel above it, not a visibility change), so the
     * full-window blur region it registers on the shade window is still composited -- measured with
     * {@code dumpsys SurfaceFlinger} while holding the bar:
     * <pre>
     *   NotificationShade#10479 ... backgroundBlurRadius=... blurRegions.size()=1
     *     {radius=76u, cornerRadii=[0,0,0,0], alpha=1.000000, rect=[0, 0, 1368, 3192]}
     * </pre>
     * 76px is exactly {@link #COVER_BLUR_RATIO} x {@code max_window_blur_radius} (382), i.e. the
     * region the module's own ramp drives for the shade, and it is what softens the desktop icons on
     * that page.  The desktop is sharp again the moment the radius (and the drawable's alpha) are 0,
     * and the normal ramp has to come back with {@code hideMirror()} -- the release the user
     * describes as "回到控制中心/通知中心主页".
     */
    private static final int MIRROR_BLUR_PX = 0;
    /**
     * Alpha handed to the cover's blur drawable while the mirror page is up.  The blur region is
     * composited through the drawable's alpha (this is the same lever the vendor pulls to fade its
     * frosted rows), so 0 is the second half of the kill: even if a stale region survived with a
     * radius, nothing is composited through it.
     */
    private static final int MIRROR_BLUR_ALPHA = 0;
    /** True between {@code BrightnessMirrorController#showMirror()} and {@code #hideMirror()}. */
    private static volatile boolean sBrightnessMirrorUp = false;
    /** The mirror container, cached: {@link #brightnessMirrorUp()} runs on every gate tick. */
    private static volatile View sMirrorPage;
    /** Guards the one-shot log line of the suppression itself. */
    private static volatile boolean sMirrorBlurLogged = false;

    /* ---------------------------- state ------------------------------- */

    private static final Map<Integer, Integer> COLOR_OVERRIDES = new HashMap<>();
    /**
     * Dark-mode counterparts of {@link #COLOR_OVERRIDES}.  A resource id in here wins while
     * {@link #sNightUi} is set; the vendor's own night variants are keyed by <em>name</em>
     * ({@code qs_slider_bg_for_night}) or by a {@code (night)} config entry of the same id, so
     * forcing only the light value used to leave every colour the module owns theme-blind.
     */
    private static final Map<Integer, Integer> COLOR_OVERRIDES_NIGHT = new HashMap<>();
    private static final Map<Integer, Integer> DIMEN_OVERRIDES = new HashMap<>();
    /** Values that only apply in landscape (portrait keeps the APK's own value). */
    private static final Map<Integer, Integer> DIMEN_LAND_OVERRIDES = new HashMap<>();
    /** Values that only apply in portrait (the page shift; landscape keeps the APK's value). */
    private static final Map<Integer, Integer> DIMEN_PORT_OVERRIDES = new HashMap<>();

    /**
     * True while SystemUI runs with {@code uiMode == UI_MODE_NIGHT_YES}, i.e. the whole module has to
     * paint with {@link #CARD_COLOR_NIGHT} and friends instead of the classic light palette.
     *
     * <p>Kept in step by two things: the initial read in {@link #handleInitPackageResources}, and a
     * hook on {@code ResourcesImpl#updateConfiguration}, which the framework calls on every theme
     * switch.  That second one matters because a night switch does not necessarily restart the
     * process -- and the <em>resource</em> overrides are consulted per {@code getValue} call, so they
     * only follow the theme while this flag is right.
     */
    private static volatile boolean sNightUi = false;
    /** Throttle for {@link #refreshNight}: the config hook is the fast path, this is a safety net. */
    private static volatile long sNightCheckedAt = 0L;
    /**
     * The classic fill colour each notification row's display list was last <em>recorded</em> with.
     *
     * <p>Written by {@link #classicCardFill} from inside the row's {@code onDraw} -- i.e. only after
     * the view really re-recorded its hardware display list with the module's fill -- and cleared by
     * {@link #applyCardTint} when the shade closes.  {@link #applyCardTint} uses the difference to
     * decide whether a row still has to be invalidated: a row whose cached list was recorded before
     * the veil came up paints the vendor's blur even though {@code setBlurBackground()} has been
     * replayed white, which is the reported "pull down into the control centre and the notification
     * cards are wrong until one is dragged" bug.  It doubles as the theme-switch log marker.
     */
    private static final Map<View, Integer> sCardFillColor = new java.util.WeakHashMap<>();
    /**
     * Scrim alpha each notification row's display list was last recorded with, i.e. the cover's own
     * alpha at that moment.  {@link ClassicCardFill#draw} lays the cover's scrim back down inside
     * the card before painting its translucent white, so a later change of the cover alpha makes the
     * cached list stale even though the fill colour did not move: during a pull the cover ramps
     * 0.01 -> 1.0, and the cards recorded with the early alpha keep a thin scrim and let their
     * blurred backdrop through (the reported "the module colour is applied first, then the card
     * changes colour").  Per row, because a row that was off screen when the ramp happened still has
     * to be repainted when it scrolls into view.  Written by {@link #classicCardFill} and compared
     * by {@link #applyCardTint}.
     */
    private static final Map<View, Integer> sCardScrim = new java.util.WeakHashMap<>();

    private static int idCover = 0x7f0a04cc;           // id/keyguard_background_frame
    private static int idWallpaperMirror = 0x7f0a0589; // id/lock_wallpaper_mirror
    private static int idWallpaperForegroundShade = 0x7f0a0bd0; // id/wallpaper_foreground_shade
    private static int idWallpaperBlurBg = 0x7f0a0bce;  // id/wallpaper_blur_bg
    private static View sMirror, sFgShade, sBlurBg;
    private static boolean sMaskApplied = false;
    private static android.graphics.drawable.Drawable sMaskOrigBackground;
    private static float sMaskOrigAlpha = 1f;
    private static int sMaskOrigVisibility = View.VISIBLE;
    private static boolean sMirrorMaskApplied = false;
    private static android.graphics.drawable.Drawable sMirrorMaskOrigBg;
    private static float sMirrorMaskOrigAlpha = 1f;
    private static int sMirrorMaskOrigVisibility = View.VISIBLE;
    /** Off: see hideWallpaperLayers() -- the shade goes black instead of showing the app. */
    private static final boolean HIDE_WALLPAPER_LAYERS = true;   // hide the wallpaper stack so the app becomes the backdrop
    private static final Map<View, Integer> sSavedVisibility = new HashMap<>();
    private static volatile boolean sMirrorBlurred = false;
    private static int sBlurMaxPx;
    private static int idSliderWrapper = 0x7f0a09c7;
    private static int idSliderIcon = 0x7f0a09c6;      // id/slider_icon
    private static int idConnectivitySliderWrapper = 0x7f0a0278;
    private static int idConnectivityTilesWrapper = 0x7f0a0279;
    private static int idMediaMiscWrapper = 0x7f0a063a;
    private static int idMiscTileWrapper = 0x7f0a066c;  // id/misc_tile_wrapper
    private static int idMediaWrapper = 0x7f0a064e;     // id/media_tile_wrapper
    private static int idMediaControls = 0x7f0a0876;    // id/qs_media_controls (the media player card)
    private static int idMediaMaskBg = 0x7f0a0638;      // id/media_mask_bg (its blurred-artwork layer)
    private static int idMiscWrapper = 0x7f0a066d;      // id/misc_wrapper
    private static int idCustomTileArea = 0x7f0a02c4;  // id/custom_tile_area
    private static int idQsContainer = 0x7f0a073a;      // id/mz_quick_settings_container
    private static int idQsCornerRadius = 0x7f070f79;  // dimen/qs_corner_radius
    private static int idCenterStatusBar = 0x7f0a0225; // id/center_status_bar_layout
    private static int idMzSystemIcons = 0x7f0a0747;   // id/mz_system_icons_container (control centre only)
    private static int idHeader = 0x7f0a0719;          // id/mz_header (StatusBarHeaderView)
    private static int idCloseFilterPanel = 0x7f0a0254; // id/close_filter_panel (filter box close glyph)
    private static int idQsScrollView = 0x7f0a0717;    // id/mz_expanded_qs_scroll_view
    private static int idTimeContainer = 0x7f0a0ab9;   // id/time_container (clock + date row)
    private static int idEditContainer = 0x7f0a0364;   // id/edit_container (tile-edit first row)
    private static int idBrightnessMirror = 0x7f0a00f6; // id/brightness_mirror_container (floating bar)
    private static int idBrightnessSlider = 0x7f0a0104; // id/brightness_slider (control-centre bar)

    private static volatile Object sKeyguardState;
    /** The two portrait dimens below are derived from the live (stock) values, so this runs once. */
    private static volatile boolean sShiftDimensSet = false;
    /** {@link #putFilterPanelShiftDimens} derives from live values too, so it also runs once. */
    private static volatile boolean sFilterShiftDimensSet = false;
    /**
     * How far the page moves down: the clock row, the control centre and the notification list all
     * take this offset, while the top status bar (signal / battery / percent) stays where it is.
     *
     * <p>Why the whole page and not just the clock row.  The notification list is not bounded by a
     * scroll limit on this build: {@code QuickSettingsControllerImpl} hands
     * {@code NotificationStackScrollLayout.setRoundedClippingBounds()} the constant
     * {@code mz_classical_panel_top_padding} (150dp = 525px) as its top edge, and the list already
     * comes to rest exactly there (measured with {@code uiautomator}) once the shade is swiped up
     * into the notification centre.  Moving the clock row alone therefore walks the clock into the
     * strip the list occupies -- the earlier 32dp attempt did exactly that, and the lunar line ended
     * up under the first card.
     *
     * <p>That edge is not a coincidence: it is the control centre container's own top edge, which the
     * list inherits through {@code MzQSImpl.getQsMinCollapsedExpansionHeight()}.  So the shift is put
     * into the resources that place the container and the list's start height
     * ({@link #putPanelShiftDimens}) and into the clock row ({@link #shiftHeaderRow(View)}), which
     * keeps the 63px (18dp) gap between the clock row and the first card intact in every state:
     * expanded, mid-drag and swiped up into the notification centre.
     */
    private static final float BELOW_CLOCK_SHIFT_DP = 32f;
    private static volatile boolean sCoverInstalled = false;    private static volatile View sCover;
    /** The cover's own scrim drawable (the plate), so a theme switch can repaint it in place. */
    private static volatile ColorDrawable sCoverScrim;
    /** Which palette {@link #sCoverScrim} currently carries. */
    private static volatile boolean sCoverScrimNight = false;
    /**
     * Every control-centre card the module has taken over: {@code slider_wrapper} and
     * {@code connectivity_tiles_wrapper}, as inflated by <em>both</em> control centres of this build
     * -- the classic shade's and the independent panel the lock screen's pull-down expands
     * ({@code control_center_view}).  Weak refs: a rotation re-inflates both panels.
     *
     * <p>This used to be one pair of fields, i.e. whichever panel inflated last.  The vendor only
     * re-inflates the two cards on a rotation, so the pair remembered there was the only surface a
     * day/night switch ever repainted; the other panel kept the fill colour it was born with, and a
     * light -&gt; dark switch left the lock screen's control centre as two white cards carrying the
     * vendor's white night text (报告: 独立面板控制中心 深色模式 通信/亮度与音量 卡片刷色).  The fill
     * itself is still re-derived from the theme on every poll -- see {@link #applyQsCardFill} -- so no
     * palette has to be remembered here.
     */
    private static final java.util.List<java.lang.ref.WeakReference<View>> sQsCards =
            new java.util.ArrayList<>();
    /** The corner radius each registered card is drawn with ({@code qs_corner_radius}). */
    private static final Map<View, Float> sQsCardRadius = new java.util.WeakHashMap<>();
    /** Notification-card tint state: the original setBlurBackground() args per row, replayed white. */
    private static final Map<View, Object[]> sCardArgs = new java.util.WeakHashMap<>();
    private static final java.util.List<java.lang.ref.WeakReference<View>> sCardViews =
            new java.util.ArrayList<>();
    private static final Map<View, Integer> sCardApplied = new java.util.WeakHashMap<>();
    /** Palette the args in {@link #sCardArgs} were recorded in, per row -- see refreshNotificationRowTheme. */
    private static final Map<View, Boolean> sCardArgsNight = new java.util.WeakHashMap<>();
    /** True while a row is being rebuilt for the current palette (lets recordCardBackground() overwrite). */
    private static volatile boolean sRowThemeRefresh = false;
    /** Palette the rows were last rebuilt for; the poller rebuilds them on a flip. */
    private static volatile boolean sRowsNight = false;
    private static volatile boolean sRowsNightInit = false;
    /** {@code ActivatableNotificationView} -- the owner of the row's theme-aware setBackground(). */
    private static volatile Class<?> sRowClass;
    /** Guards the one-shot failure log of the row rebuild. */
    private static volatile boolean sRowRefreshFailed = false;
    /** Every live {@code StatusBarHeaderView} (the classic shade's and the lock screen CC's). */
    private static final java.util.List<java.lang.ref.WeakReference<View>> sHeaderViews =
            new java.util.ArrayList<>();
    /** Every live {@code QSTileViewImpl}, so a theme flip can re-run the vendor's own colour pass. */
    private static final java.util.List<java.lang.ref.WeakReference<View>> sTileViews =
            new java.util.ArrayList<>();
    /**
     * Depth of {@code QSTileViewImpl} constructions on the calling thread.
     *
     * <p>That constructor caches its colours in <em>final fields</em> as a light/night pair
     * ({@code colorInactive} / {@code colorInactiveForNight}), so a night value served for a light id
     * lands in the field that is only read in light mode.  The four tile ids no longer carry a night
     * value at all (see hook 1), which is what actually fixes 深色切浅色后磁贴仍是深灰; this depth is
     * the guard that keeps a future night override out of those fields.
     */
    private static final ThreadLocal<Integer> sTileCtorDepth = new ThreadLocal<>();
    /**
     * Media-card tint state: the module's own fill per player card, the colour it carries, the
     * vendor artwork lifted off the card's mask layer, and the card view itself.
     *
     * <p>Same shape as the row state above ({@link #sCardFillColor} / {@link #sCardApplied}) and for
     * the same reason: the vendor rebuilds the surface on every media/mode change, so the fill is
     * re-applied by both a hook on the builder and the shade poller, and both need to recognise
     * "this card is already ours" without comparing drawables it cannot see.
     */
    private static final Map<View, ClassicCardFill> sMediaCardFill = new java.util.WeakHashMap<>();
    private static final Map<View, Integer> sMediaCardApplied = new java.util.WeakHashMap<>();
    private static final Map<View, android.graphics.drawable.Drawable> sMediaMaskArt =
            new java.util.WeakHashMap<>();
    private static java.lang.ref.WeakReference<View> sMediaCard =
            new java.lang.ref.WeakReference<>(null);
    /** Guards the re-entrancy of our own {@code setBackground()} call on the media card. */
    private static volatile boolean sMediaFilling = false;
    private static volatile boolean sMediaCardLogged = false;
    private static volatile boolean sCardLogged = false;
    private static volatile long sLastCardLog = 0L;
    private static volatile boolean sCardReplayFailed = false;
    private static volatile long sLastTintLog = 0L;
    private static volatile Object sManager;
    private static volatile Object sPanelController;
    /** The Flyme control-centre panel controller ({@code CenterController}); the lock-screen pull-down expands it. */
    private static volatile Object sCenter;
    private static volatile boolean sPanelVisible = false;
    private static volatile float sWallpaperAlpha = 0f;
    /** The raw drag drive published by applyCoverAlpha(). */
    private static volatile float sBlurDrive = 0f;
    /** The mask alpha applyCoverAlpha() last put on the cover -- frames the blur strength. */
    private static volatile float sMaskAlpha = 0f;
    private static volatile boolean sTintLogged = false;
    private static volatile boolean sExpansionLogged = false;
    private static volatile long sLastAlphaLog = 0L;
    private static volatile long sLastExpLog = 0L;
    /** {@code dimen/status_bar_headerview_height}, the height both control centres' headers want. */
    private static int idHeaderHeight = 0x7f07112d;
    private static volatile ClassLoader sClassLoader;
    private static volatile boolean sKgLogged = false;
    private static volatile boolean sShadeOpen = false;
    private static final java.util.Map<View, Float> sSliderRadius = new java.util.WeakHashMap<>();

    /* ================================================================== */
    /*  Hook 1: resource value funnel                                      */
    /* ================================================================== */

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        if (!SYSUI.equals(resparam.packageName)) return;
        try {
            final Resources res = resparam.res;
            sNightUi = isNight(res);

            putColor(res, "qs_tile_active_bg_color", CARD_COLOR);
            putColor(res, "qs_tile_inactive_bg_color_for_low_visual_quality", CARD_COLOR);
            putColor(res, "qs_connectivity_wrapper_bg", CARD_COLOR);
            // The tile grid keeps the notification card's colour rather than an opaque white, so the
            // tiles read as the same surface as the cards above them and the notification rows.  It
            // is a plain colour override: the tile background is its own drawable
            // (`qs_tile_inactive_bg_color` is read through `drawable/qs_tile_bg`), not one of the two
            // control-centre cards, so nothing has to be punched out from under it -- the tiles have
            // nothing but the backdrop behind them.
            putColor(res, "qs_tile_inactive_bg_color", CARD_BG_COLOR);
            putColor(res, "qs_square_tile_inactive_bg_color", CARD_BG_COLOR);
            putColor(res, "qs_slider_bg", SLIDER_TRACK_COLOR);
            putColor(res, "qs_header_text_color", HEADER_TEXT_COLOR);

            // ... and the dark-mode counterparts of the same seven.  The vendor reaches its night
            // colours two ways -- by name (`qs_slider_bg_for_night`, which the module must NOT
            // neutralise) and through a `(night)` config entry of the id being overridden (which is
            // what turned the white plate into the unreadable mix this fixes).  Setting the night map
            // makes the module's own value theme-correct in both cases.
            //
            // NOT overridden for the four tile colours below (deliberately): their night values are
            // separate ids (`qs_tile_*_for_night`, the vendor's own #80000000) which QSTileViewImpl
            // reads into its own light/night pair of fields.  Overriding the *light* ids' night value
            // made every lookup of them return the night colour, which the tile then cached in the
            // field it reads in light mode (and ResourcesImpl's colour cache kept it, so the
            // construction-time guard in hook 1 could not save it either) -- 报告 2026-10-17:
            // 深色切浅色后磁贴仍是深灰.
            // `qs_tile_active_bg_color`, `qs_tile_inactive_bg_color`,
            // `qs_tile_inactive_bg_color_for_low_visual_quality`,
            // `qs_square_tile_inactive_bg_color`: light value only.
            putColorNight(res, "qs_connectivity_wrapper_bg", CARD_COLOR_NIGHT);
            putColorNight(res, "qs_slider_bg", SLIDER_TRACK_COLOR_NIGHT);
            putColorNight(res, "qs_slider_bg_for_night", SLIDER_TRACK_COLOR_NIGHT);
            putColorNight(res, "qs_slider_bg_for_press", SLIDER_TRACK_PRESS_COLOR_NIGHT);
            putColorNight(res, "qs_slider_bg_for_press_for_night", SLIDER_TRACK_PRESS_COLOR_NIGHT);
            putColorNight(res, "qs_header_text_color", HEADER_TEXT_COLOR_NIGHT);
            // Landscape: this is what separates the header row from the notification cards; the
            // stock 76dp leaves the row almost touching the content underneath.
            putDimen(res, "classic_notification_top_padding", LS_NOTIFICATION_TOP_PADDING_DP);
            // Landscape: the notification list's clipping/rest edge is
            // mz_classical_panel_top_padding, stock 72dp -- 25dp above the 97dp line the expanded
            // panel uses.  Swiping up into the notification centre therefore let the cards climb
            // to 250px; put that edge on the same 97dp so they stop at 336px in every state.
            // alignQsColumn drops its 25dp push-down to keep the control-centre column in place.
            putDimenLand(res, "mz_classical_panel_top_padding", LS_NOTIFICATION_TOP_PADDING_DP);
            // both cards (connectivity + sliders) size themselves from this single dimen; the
            // stock sw343dp override drops it to 172dp while the old cards were 179.7dp tall.
            putDimen(res, "qs_connectivity_slider_wrapper_height", LS_CARD_HEIGHT_DP);
            // the one dimen that sizes the classic QS region and the notification start together
            putDimen(res, "qs_min_expansion_height", LS_QS_MIN_EXPANSION_DP
                    + (LS_CARD_HEIGHT_DP + LS_REGION_SLACK_DP - LS_QS_REGION_BASE_DP));

            // ... and the portrait half of the page shift, derived from those two values
            putPanelShiftDimens(res);

            // ... and the "收纳箱 / 不重要通知" panel, pushed down to the clock's line
            putFilterPanelShiftDimens(res);

            // ... and the ongoing-privacy chip's row (portrait up, landscape down + flush with the
            // notification column -- the horizontal half is a code hook, see hookPrivacyChipAlign)
            putPrivacyChipRowDimens(res);

            // These three are read straight from resources by StatusBarHeaderView and QSStatusBar,
            // so the orientation-specific values can live in resources instead of code hooks.
            putDimenLand(res, "classical_flyme_clock_text_size", LS_CLOCK_DP);
            putDimenLand(res, "flyme_lunar_date_view_top_margin_for_vertical", LS_LUNAR_TOP_MARGIN_DP);
            putDimenLand(res, "flyme_lunar_date_view_top_margin_for_horizatal", LS_LUNAR_TOP_MARGIN_DP);
            putDimenLand(res, "control_center_status_bar_margin_top", LS_STATUS_BAR_TOP_DP);
            // DELIBERATELY NOT OVERRIDDEN: `status_bar_padding_end`.  It is the end margin of EVERY
            // status bar in the system (PhoneStatusBarView#updatePaddings -> status_bar_contents,
            // KeyguardStatusBarView, KeyguardBouncerStatusBarView, NotificationTickController and
            // only then QSStatusBar#loadDimens), so a landscape override here moves the *normal*
            // landscape status bar 48dp to the left as well.  The control centre's own end margin
            // is applied to its own view by hook 5c instead -- same 68dp, one owner.

            int v = res.getIdentifier("keyguard_background_frame", "id", SYSUI);
            if (v != 0) idCover = v;
            v = res.getIdentifier("lock_wallpaper_mirror", "id", SYSUI);
            if (v != 0) idWallpaperMirror = v;
            v = res.getIdentifier("wallpaper_foreground_shade", "id", SYSUI);
            if (v != 0) idWallpaperForegroundShade = v;
            v = res.getIdentifier("wallpaper_blur_bg", "id", SYSUI);
            if (v != 0) idWallpaperBlurBg = v;
            v = res.getIdentifier("slider_wrapper", "id", SYSUI);
            if (v != 0) idSliderWrapper = v;
            v = res.getIdentifier("slider_icon", "id", SYSUI);
            if (v != 0) idSliderIcon = v;
            v = res.getIdentifier("connectivity_slider_wrapper", "id", SYSUI);
            if (v != 0) idConnectivitySliderWrapper = v;
            v = res.getIdentifier("connectivity_tiles_wrapper", "id", SYSUI);
            if (v != 0) idConnectivityTilesWrapper = v;
            v = res.getIdentifier("media_misc_wrapper", "id", SYSUI);
            if (v != 0) idMediaMiscWrapper = v;
            v = res.getIdentifier("media_tile_wrapper", "id", SYSUI);
            if (v != 0) idMediaWrapper = v;
            v = res.getIdentifier("qs_media_controls", "id", SYSUI);
            if (v != 0) idMediaControls = v;
            v = res.getIdentifier("media_mask_bg", "id", SYSUI);
            if (v != 0) idMediaMaskBg = v;
            v = res.getIdentifier("misc_tile_wrapper", "id", SYSUI);
            if (v != 0) idMiscTileWrapper = v;
            v = res.getIdentifier("misc_wrapper", "id", SYSUI);
            if (v != 0) idMiscWrapper = v;
            v = res.getIdentifier("custom_tile_area", "id", SYSUI);
            if (v != 0) idCustomTileArea = v;
            v = res.getIdentifier("mz_quick_settings_container", "id", SYSUI);
            if (v != 0) idQsContainer = v;
            v = res.getIdentifier("qs_corner_radius", "dimen", SYSUI);
            if (v != 0) idQsCornerRadius = v;
            v = res.getIdentifier("mz_expanded_qs_scroll_view", "id", SYSUI);
            if (v != 0) idQsScrollView = v;
            v = res.getIdentifier("center_status_bar_layout", "id", SYSUI);
            if (v != 0) idCenterStatusBar = v;
            v = res.getIdentifier("mz_system_icons_container", "id", SYSUI);
            if (v != 0) idMzSystemIcons = v;
            v = res.getIdentifier("status_bar_padding_end", "dimen", SYSUI);
            if (v != 0) idStatusBarPaddingEnd = v;
            v = res.getIdentifier("status_bar_headerview_height", "dimen", SYSUI);
            if (v != 0) idHeaderHeight = v;
            v = res.getIdentifier("backgroundFlyme", "id", SYSUI);
            if (v != 0) idBackgroundFlyme = v;
            v = res.getIdentifier("mz_header", "id", SYSUI);
            if (v != 0) idHeader = v;
            v = res.getIdentifier("time_container", "id", SYSUI);
            if (v != 0) idTimeContainer = v;
            v = res.getIdentifier("edit_container", "id", SYSUI);
            if (v != 0) idEditContainer = v;
            v = res.getIdentifier("brightness_mirror_container", "id", SYSUI);
            if (v != 0) idBrightnessMirror = v;
            v = res.getIdentifier("brightness_slider", "id", SYSUI);
            if (v != 0) idBrightnessSlider = v;
            v = res.getIdentifier("close_filter_panel", "id", SYSUI);
            if (v != 0) idCloseFilterPanel = v;

            Object impl = XposedHelpers.getObjectField(res, "mResourcesImpl");
            if (impl == null) {
                log("1 resources: mResourcesImpl null");
                return;
            }
            XposedBridge.hookAllMethods(impl.getClass(), "getValue", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length != 3 || !(param.args[0] instanceof Integer)) return;
                    int id = (Integer) param.args[0];
                    Object out = param.args[1];
                    if (!(out instanceof TypedValue)) return;
                    // The night value owns the id while the theme is dark; a colour that has no night
                    // counterpart (there is none today) simply falls back to the light one.  While a
                    // tile view is being constructed only the light value is served: QSTileViewImpl
                    // caches these colours in final fields as a light/night pair, so a night value
                    // would land in the field it reads in light mode (报告 2026-10-17: 磁贴 深色转浅色
                    // 停在深灰).  See sTileCtorDepth; the four tile ids themselves no longer carry a
                    // night value at all -- that was the actual fix, see the putColorNight note below.
                    Integer c = (sNightUi && tileCtorDepth() == 0)
                            ? COLOR_OVERRIDES_NIGHT.get(id) : null;
                    if (c == null) c = COLOR_OVERRIDES.get(id);
                    if (c != null) {
                        TypedValue tv = (TypedValue) out;
                        tv.type = TypedValue.TYPE_INT_COLOR_ARGB8;
                        tv.data = c;
                        tv.string = null;
                        return;
                    }
                    Integer d = DIMEN_OVERRIDES.get(id);
                    Integer land = DIMEN_LAND_OVERRIDES.get(id);
                    Integer port = DIMEN_PORT_OVERRIDES.get(id);
                    // The orientation-specific values must be consulted FIRST: if one were only
                    // applied as an override of `d`, then any dimen that also has a base override
                    // (putDimen) would never take its orientation value -- the base assignment would
                    // silently win in both orientations, which is what distorted the landscape panel.
                    int orientation = res.getConfiguration().orientation;
                    if (land != null
                            && orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        d = land;
                    } else if (port != null
                            && orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                        d = port;
                    }
                    if (d != null) {
                        TypedValue tv = (TypedValue) out;
                        tv.type = TypedValue.TYPE_DIMENSION;
                        tv.data = (d << TypedValue.COMPLEX_MANTISSA_SHIFT) | TypedValue.COMPLEX_UNIT_PX;
                        tv.string = null;
                    }
                }
            });

            // The theme switch itself.  SystemUI does not have to be re-created for uiMode to flip
            // (the settings toggle only sends a configuration change), and both the resource values
            // above and every colour this module paints are read from sNightUi -- so it has to follow
            // the configuration, not just the process start.
            XposedBridge.hookAllMethods(impl.getClass(), "updateConfiguration", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    for (Object a : param.args) {
                        if (a instanceof android.content.res.Configuration) {
                            sNightUi = ((((android.content.res.Configuration) a).uiMode)
                                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                            sNightCheckedAt = android.os.SystemClock.uptimeMillis();
                            break;
                        }
                    }
                }
            });
            log("1 resources hooked: " + COLOR_OVERRIDES.size() + " colours ("
                    + COLOR_OVERRIDES_NIGHT.size() + " with a night value), night=" + sNightUi);
        } catch (Throwable t) {
            log("1 resources FAILED");
            XposedBridge.log(t);
        }
    }

    private static void putColor(Resources res, String name, int color) {
        int id = res.getIdentifier(name, "color", SYSUI);
        if (id != 0) COLOR_OVERRIDES.put(id, color);
        else log("1 missing colour " + name);
    }

    /** Dark-mode counterpart of {@link #putColor}. */
    private static void putColorNight(Resources res, String name, int color) {
        int id = res.getIdentifier(name, "color", SYSUI);
        if (id != 0) COLOR_OVERRIDES_NIGHT.put(id, color);
        else log("1 missing night colour " + name);
    }

    /* ---------------------------- night mode ---------------------------- */

    /** True when this Resources is configured for the dark theme. */
    private static boolean isNight(Resources res) {
        if (res == null) return false;
        return (res.getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Re-read the night flag from a live Resources, at most once a second.
     *
     * <p>{@link #sNightUi} is normally driven by the {@code ResourcesImpl#updateConfiguration} hook,
     * which is the exact moment the framework switches a theme.  This is the cheap safety net for the
     * paths that run off a View (the cover poller, the card fills): even if a configuration change
     * never reached that hook, the panel is at most a second away from the right palette.
     */
    private static boolean refreshNight(Resources res) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sNightCheckedAt > 1000) {
            sNightCheckedAt = now;
            boolean night = isNight(res);
            if (night != sNightUi) {
                sNightUi = night;
                log("night mode -> " + night + " (panel repainted)");
            }
        }
        return sNightUi;
    }

    /**
     * True while the module must stay out of the vendor's ink decisions.
     *
     * <p>In light mode the classic panel is a white plate, so the clock, the date, the centre status
     * bar and the "收纳箱" box all have to be forced dark -- the platform derives them from the
     * lock-screen wallpaper, which is not what is on screen.  In dark mode the requested behaviour is
     * the opposite: no forcing at all, the vendor's own automatic colour decides ("关闭强制黑色，
     * 试试系统的自动颜色").  Every ink hook tests this.
     */
    private static boolean autoInk() {
        return sNightUi;
    }

    /** The card fill behind the sliders / connectivity rows (opaque white, or 50% black at night). */
    private static int cardColor() {
        return sNightUi ? CARD_COLOR_NIGHT : CARD_COLOR;
    }

    /** The notification row's own fill. */
    private static int notificationFill() {
        return sNightUi ? CARD_BG_COLOR_NIGHT : CARD_BG_COLOR;
    }

    /** The veil painted over the blurred backdrop. */
    private static int coverScrimColor() {
        return sNightUi ? COVER_SCRIM_COLOR_NIGHT : COVER_SCRIM_COLOR;
    }

    /**
     * Alpha the cover's scrim currently carries, which is also the alpha
     * {@link ClassicCardFill#draw} has to lay back down inside a card.  Kept in one place so the
     * drawable and the repaint bookkeeping can never disagree.
     */
    private static int cardScrimAlpha() {
        View cover = sCover;
        int coverAlpha = cover == null ? 255 : Math.round(255f * cover.getAlpha());
        return android.graphics.Color.alpha(coverScrimColor()) * coverAlpha / 255;
    }

    private static void putDimen(Resources res, String name, float dp) {
        int id = res.getIdentifier(name, "dimen", SYSUI);
        if (id == 0) {
            log("1 missing dimen " + name);
            return;
        }
        DIMEN_OVERRIDES.put(id, Math.round(dp * res.getDisplayMetrics().density));
    }

    /**
     * Landscape-only value: the APK keeps its own value in portrait.  Several of the classic panel
     * keys are read straight from resources by the header / shade status bar, so overriding them
     * replaces whole code hooks.
     */
    private static void putDimenLand(Resources res, String name, float dp) {
        int id = res.getIdentifier(name, "dimen", SYSUI);
        if (id == 0) {
            log("1 missing dimen " + name);
            return;
        }
        DIMEN_LAND_OVERRIDES.put(id, Math.round(dp * res.getDisplayMetrics().density));
    }

    /**
     * The portrait half of the page shift -- see {@link #BELOW_CLOCK_SHIFT_DP}.
     *
     * <p>Two dimens move the whole control centre and the notification list together, because both
     * regions are placed from them:
     * <ul>
     *   <li>{@code mz_classical_panel_top_padding} is the control centre container's
     *       {@code topMargin} ({@code MzQSImpl.updateContainerPosition()}) <em>and</em> the top edge
     *       of the notification list's clipping rectangle ({@code QuickSettingsControllerImpl});
     *       it is also what {@code MzQSImpl.getQsMinCollapsedExpansionHeight()} reports through
     *       {@code mMzQSContainer.getY()}, i.e. where the list comes to rest once the shade is
     *       swiped up into the notification centre.</li>
     *   <li>{@code qs_min_expansion_height} is the list's start height while the control centre is
     *       expanded ({@code mExpansionHeight} comes from {@code setExpansionHeight()}, clamped to
     *       this minimum) and the container's clip height, so the region keeps its size.</li>
     * </ul>
     *
     * <p>Deriving from the live value keeps this a true 32dp shift in every configuration (the
     * stock value is 150dp in portrait, 72dp in landscape -- landscape is left alone).
     */
    private static void putPanelShiftDimens(Resources res) {
        if (sShiftDimensSet) return;
        sShiftDimensSet = true;
        int add = Math.round(BELOW_CLOCK_SHIFT_DP * res.getDisplayMetrics().density);
        addPortDimenShift(res, "mz_classical_panel_top_padding", add, 150f);
        // same expression hook 1 puts into DIMEN_OVERRIDES, so the fallback matches the base
        addPortDimenShift(res, "qs_min_expansion_height", add,
                LS_QS_MIN_EXPANSION_DP + (LS_CARD_HEIGHT_DP + LS_REGION_SLACK_DP - LS_QS_REGION_BASE_DP));
        // The clock row now ends below the header's own bottom edge, and the header cannot simply
        // stop clipping: its content is composited through a layer sized to its bounds (clearing
        // clipChildren/clipToPadding on it changes nothing on this build -- measured).  Growing the
        // header by the same amount keeps the row inside it, which needs no clip work at all.  The
        // row is placed by the header's top padding, so it does not move; only the bounds grow.
        addPortDimenShift(res, "status_bar_headerview_height", add, 138f);
    }

    /**
     * The portrait half of the "收纳箱 / 不重要通知" panel shift -- see
     * {@link #FILTER_PANEL_SHIFT_DP}.
     *
     * <p>Both dimens are classic-mode values read by
     * {@code NotificationFilterPanel#updateHeaderMarginTop()} while {@code isUseClassicsStyle()} is
     * true (it is, on this build), and both are taken from the live APK value so the shift stays a
     * true offset in portrait and landscape alike.  {@code filter_header_margin_top_portrait} and
     * {@code notification_filter_panel_stack_margin_top} are the non-classic counterparts -- the
     * panel only ever reads one pair, but the live read below is what decides the value, so touching
     * both is unnecessary.  Hook 8 already holds the panel's colours; this is purely geometry.
     */
    private static void putFilterPanelShiftDimens(Resources res) {
        if (sFilterShiftDimensSet) return;
        sFilterShiftDimensSet = true;
        // All three constants are dp on the screen; dpToWrittenPx() turns them into the number the
        // dimension has to carry, so the same dp geometry lands on a display of any density.
        int add = dpToWrittenPx(res, FILTER_PANEL_SHIFT_DP);              // 46dp -> 161 written
        log("1 filter panel shift: " + FILTER_PANEL_SHIFT_DP + "dp -> " + add
                + " written px (the layout uses the number as screen px on this build)");
        addPortDimenShift(res, "classic_notification_filter_header_margin_top", add,
                FILTER_HEADER_MARGIN_TOP_STOCK_DP);
        addPortDimenShift(res, "classic_notification_filter_panel_stack_margin_top", add,
                FILTER_STACK_MARGIN_TOP_STOCK_DP);

        // Landscape: the title row stays where stock puts it (it is already on the clock's line);
        // only the list moves, by 25dp = 88px here (252 -> ~340).  Same dp -> written px rule as
        // portrait, so the offset follows the display density too.
        int addLand = dpToWrittenPx(res, FILTER_PANEL_SHIFT_LAND_DP);
        addLandDimenShift(res, "classic_notification_filter_panel_stack_margin_top", addLand,
                FILTER_STACK_MARGIN_TOP_LAND_STOCK_DP);
        // ... and the title itself, which stock draws 11dp smaller than the control centre's title.
        putDimenLand(res, "classic_notification_panel_title_size", FILTER_TITLE_SIZE_LAND_DP);
    }

    /** {@link #addPortDimenShift} for landscape: the APK's portrait value is left alone. */
    private static void addLandDimenShift(Resources res, String name, int addPx, float stockLandscapeDp) {
        int id = res.getIdentifier(name, "dimen", SYSUI);
        if (id == 0) {
            log("1 missing land shift dimen " + name);
            return;
        }
        Integer base = DIMEN_OVERRIDES.get(id);
        int px;
        if (base != null) {
            px = base;
        } else if (res.getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            px = res.getDimensionPixelSize(id);
        } else {
            px = Math.round(stockLandscapeDp * res.getDisplayMetrics().density);
        }
        DIMEN_LAND_OVERRIDES.put(id, px + addPx);
        log("1 land shift dimen " + name + ": " + px + " -> " + (px + addPx) + " px");
    }

    /**
     * Converts a dp distance on the screen into the number that has to be written into a dimension
     * resource for the filter panel to move by exactly that much.
     *
     * <p>Measured on this device by sweeping the written value and reading the panel's title ink out
     * of the screenshots (560dpi, {@code density = 3.5}):
     * <pre>
     *   written 143 -> title ink top 429px
     *   written 250 -> 536px        =>  one pixel of travel per unit written,
     *   written 350 -> 636px            title_top = 286 + written
     * </pre>
     * so the written number is a screen-pixel count and the conversion is the plain dp -> px one:
     * {@code written = dp * densityDpi / 160}.  40.86dp of travel is 143px here, and that is what puts
     * the title on the clock's 446px line.
     *
     * <p>Which is exactly what makes the panel device-independent when the constants are dp: another
     * density writes a proportionally larger number and the panel moves the same dp distance.  All the
     * constants in this class are the dp distance they represent at the nominal 160dpi.
     */
    private static int dpToWrittenPx(Resources res, float dp) {
        return Math.round(dp * res.getConfiguration().densityDpi / 160f);
    }

    /**
     * The ongoing-privacy chip's row, portrait half -- see {@link #CHIP_ROW_UP_PORTRAIT_DP}.
     *
     * <p>Portrait places the row with {@code ongoing_filter_group}'s paddingTop, written by
     * {@code NotificationPanelViewController#updatePadding} from
     * {@code notification_ongoing_filter_group_padding_top} (38dp, a {@code getDimensionPixelSize}
     * read, so the override lands).  38 - 10.8dp = 27.2dp lifts the chip the measured 38px onto the
     * status bar's line.
     *
     * <p>Landscape cannot be done with a dimen: there {@code updatePadding} zeroes that padding
     * ({@code isExpandToQsEnabled}), and the offset that is left comes from
     * {@code ongoing_chip_wrapper_padding_vertical}, which
     * {@code NotificationsQuickSettingsContainer#updateOngoingPrivacyChipMaxWidth} only reads once,
     * when the shade is inflated -- a rotation never re-reads it, so overriding it left the chip
     * exactly where it was (measured).  The landscape half is therefore measured live in
     * {@link #alignPrivacyChipRow} instead.
     *
     * <p>Only dimens that the vendor reads <em>in code</em> can be overridden at all: a dimen that
     * only appears as a layout attribute is resolved natively by {@code AssetManager.applyStyle} and
     * never reaches the {@code ResourcesImpl#getValue} hook (measured with
     * {@code ongoing_chip_wrapper_margin_top}, the wrapper's {@code layout_marginTop}: the chip did
     * not move by a pixel).
     */
    private static void putPrivacyChipRowDimens(Resources res) {
        int groupTop = res.getIdentifier("notification_ongoing_filter_group_padding_top", "dimen", SYSUI);
        if (groupTop == 0) {
            log("1 missing dimen notification_ongoing_filter_group_padding_top");
            return;
        }
        int stock = Math.round(CHIP_GROUP_TOP_STOCK_DP * res.getDisplayMetrics().density);
        int px = Math.round((CHIP_GROUP_TOP_STOCK_DP - CHIP_ROW_UP_PORTRAIT_DP)
                * res.getDisplayMetrics().density);
        DIMEN_PORT_OVERRIDES.put(groupTop, px);
        log("1 privacy chip row portrait: group paddingTop " + stock + " -> " + px + "px");
    }

    /**
     * @param stockPortraitDp value the APK carries in portrait, used only when the process happened
     *                        to initialise while the device was already rotated -- a live read would
     *                        then hand back the landscape variant.
     */
    private static void addPortDimenShift(Resources res, String name, int addPx, float stockPortraitDp) {
        int id = res.getIdentifier(name, "dimen", SYSUI);
        if (id == 0) {
            log("1 missing shift dimen " + name);
            return;
        }
        Integer base = DIMEN_OVERRIDES.get(id);
        int px;
        if (base != null) {
            px = base;                       // an absolute override: orientation-independent already
        } else if (res.getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            px = res.getDimensionPixelSize(id);
        } else {
            px = Math.round(stockPortraitDp * res.getDisplayMetrics().density);
        }
        DIMEN_PORT_OVERRIDES.put(id, px + addPx);
        log("1 shift dimen " + name + ": " + px + " -> " + (px + addPx) + " px");
    }

    /* ================================================================== */
    /*  Hooks 2..7                                                         */
    /* ================================================================== */

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSUI.equals(lpparam.packageName)) return;
        sClassLoader = lpparam.classLoader;
        XposedHelpers.findAndHookMethod("android.provider.Settings$System", sClassLoader, "getStringForUser", android.content.ContentResolver.class, String.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (param.args[1].equals("notification_and_control_center"))
                    param.setResult("1");
            }
        });
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.flyme.systemui.wallpaper.LockscreenWallpaperManager", lpparam.classLoader);

            XposedBridge.hookAllMethods(manager, "setNotificationShadeWindowView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        sManager = param.thisObject;
                        Object root = param.args[0];
                        if (root instanceof ViewGroup) installCover((ViewGroup) root);
                    } catch (Throwable t) {
                        log("2 installCover FAILED");
                        XposedBridge.log(t);
                    }
                }
            });
            log("2 hook setNotificationShadeWindowView");

            hookViewColor("com.flyme.systemui.statusbar.phone.StatusBarHeaderView",
                    lpparam.classLoader, "4 StatusBarHeaderView");
            Class<?> headerCls = XposedHelpers.findClass(
                    "com.flyme.systemui.statusbar.phone.StatusBarHeaderView", lpparam.classLoader);
            // Header / tile instances for the theme re-ink (hook 4d): nothing in SystemUI re-applies
            // their ink on a uiMode change, so the module triggers the vendor's own passes.
            hookThemeAwareViews(lpparam.classLoader, headerCls);
            XposedBridge.hookAllMethods(headerCls, "updateDateLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        fixHeader((View) param.thisObject);
                    } catch (Throwable t) {
                        log("4b fixHeader FAILED");
                        XposedBridge.log(t);
                    }
                }
            });
            log("4b hook StatusBarHeaderView.updateDateLocation");
            // The row shift cannot come from updateDateLocation (landscape only) nor from
            // StatusBarHeaderView#onLayout (not overridden, so hookAllMethods finds nothing).
            // RelativeLayout#onLayout is the method the header actually inherits; filter by type.
            final Class<?> hdr = headerCls;
            XposedBridge.hookAllMethods(android.widget.RelativeLayout.class, "onLayout",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object o = param.thisObject;
                            if (o instanceof View && hdr.isInstance(o)) {
                                shiftHeaderRow((View) o);
                                shiftEditHeaderRow((View) o);
                            }
                        }
                    });
            log("4c hook RelativeLayout.onLayout (header row shift)");

            // The floating brightness bar needs x correction -- see BRIGHTNESS_MIRROR_OFFSET_DP.
            hookBrightnessMirrorAlign(lpparam.classLoader);
            // ... and the page it opens must not blur the desktop behind it.
            hookBrightnessMirrorBlur(lpparam.classLoader);

            // The shade status row's landscape geometry needs no code hook for its top margin:
            // QSStatusBar reads control_center_status_bar_margin_top from resources and that one is
            // overridden for landscape in hook 1.  Its *end* margin is a different story -- the
            // dimen it would come from is shared with every other status bar -- so it is applied to
            // the control centre's own container by hook 5c below.
            hookQsStatusBarEndMargin(lpparam.classLoader);
            hookShadeWindowBlur(lpparam.classLoader);




            hookViewColor("com.flyme.systemui.controlcenter.qs.QSStatusBarController",
                    lpparam.classLoader, "5 QSStatusBarController");
            hookQsIconTint(lpparam.classLoader);

            // keep a handle on the shade controller: getExpandedFraction() is the reliable
            // "shade open" signal for this build.
            Class<?> npvc = XposedHelpers.findClass(
                    "com.android.systemui.shade.NotificationPanelViewController", lpparam.classLoader);
            XposedBridge.hookAllConstructors(npvc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sPanelController = param.thisObject;
                    log("3c NotificationPanelViewController captured");
                }
            });

            // The control centre is its own panel (Flyme's CenterController) and it is what the
            // lock screen's pull-down actually expands -- see the lock-screen animation hooks.
            try {
                Class<?> center = XposedHelpers.findClass(
                        "com.flyme.systemui.controlcenter.phone.CenterController", lpparam.classLoader);
                XposedBridge.hookAllConstructors(center, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sCenter = param.thisObject;
                        log("3d CenterController captured");
                    }
                });
            } catch (Throwable t) {
                log("3d CenterController hook failed: " + t);
            }

            // The lock screen's pull-down expands that panel, and the old design's transition is
            // rebuilt on top of it -- see the "classic lock-screen pull-down" section.
            hookClassicLockPull(lpparam.classLoader);

            Class<?> panel = XposedHelpers.findClass(
                    "com.flyme.systemui.controlcenter.phone.MzQSPanel", lpparam.classLoader);
            XposedBridge.hookAllMethods(panel, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        styleSliderWrapper((View) param.thisObject);
                    } catch (Throwable t) {
                        log("6 styleSliderWrapper FAILED");
                        XposedBridge.log(t);
                    }
                }
            });
            log("6 hook MzQSPanel.onFinishInflate");

            XposedBridge.hookAllMethods(panel, "updateResources", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        alignQsColumn((View) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                    try {
                        keepTileGridGap((View) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            log("6b hook MzQSPanel.updateResources");

            // The vendor hides this row from its own visibility pass, which can run after
            // updateResources(); re-assert the row right after it so the band its topMargin carries
            // is measured in the next layout.
            XposedBridge.hookAllMethods(panel, "setMediaWrapperVisibility", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        keepTileGridGap((View) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            log("6f hook MzQSPanel.setMediaWrapperVisibility");

            XposedBridge.hookAllMethods(panel, "onLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        narrowSliderTracks((View) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            log("6c hook MzQSPanel.onLayout");

            hookNotificationCardTint(lpparam.classLoader);

            hookMediaCardTint(lpparam.classLoader);

            hookFilterPanel(lpparam.classLoader);

            hookPrivacyChip(lpparam.classLoader);

            hookPrivacyChipRow(lpparam.classLoader);

            hookEmptyShadeView(lpparam.classLoader);

            log("ALL hooks installed");
        } catch (Throwable t) {
            log("handleLoadPackage FAILED");
            XposedBridge.log(t);
        }
    }

    /**
     * Hook 7 -- notification card fill.
     *
     * <p>The classic card must read as an opaque white surface lying on the control centre's white
     * veil.  The vendor's card is the opposite: one {@code BackgroundBlurDrawable} carrying both a
     * frosted sample of the backdrop and a 70% white fill ({@code 0xB2FFFFFF}), whose alpha is
     * re-driven on every row state change -- a 100px drag over a card measurably drops it from
     * 247 to 217 and brings the wallpaper back through it.
     *
     * <p>So the fill is taken over rather than negotiated:
     * <ul>
     *   <li>{@code NotificationBackgroundView.onDraw} -- while the veil is up, the card's own layer
     *       paints an opaque rounded fill and the vendor's drawable is bypassed entirely.  This is
     *       the one path the vendor's animations cannot undo.</li>
     *   <li>{@code setRadius} -- mirrors the vendor's corner radii onto that fill so the shape is
     *       unchanged.</li>
     *   <li>{@code setBlurBackground} / {@code setDrawableAlpha} / {@code updateLiveBlurAlpha} --
     *       the vendor's blur fill is still recoloured to the classic white and its alpha pinned,
     *       which covers the frames before the first {@code onDraw} and the rows that take part in
     *       the shade animation.</li>
     *   <li>{@code ActivatableNotificationView.setBackground} -- re-applies the recolour whenever
     *       the vendor re-creates the drawable (attach, media state, new notification).</li>
     * </ul>
     */
    private static void hookNotificationCardTint(ClassLoader cl) {
        try {
            Class<?> bgView = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.NotificationBackgroundView", cl);
            XposedBridge.hookAllMethods(bgView, "setBlurBackground", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View) || param.args.length < 2
                            || !(param.args[1] instanceof Integer)) {
                        return;
                    }
                    View v = (View) param.thisObject;
                    recordCardBackground(v, param.args);
                    logCardState(v, (Integer) param.args[1]);
                }
            });
            log("7 hook NotificationBackgroundView.setBlurBackground");

            // The row's own colour is re-applied as a drawable alpha by the vendor whenever the
            // panel or the row changes state (updateLiveBlurAlpha -> setBackgroundBlurDrawableAlpha
            // -> setDrawableAlpha).  It is the one path that can make a card see-through again
            // without ever calling setBlurBackground, so it is watched from both ends: while the
            // classic veil is up the alpha is forced opaque, and the event is logged so a
            // regression can be attributed to a concrete caller.
            XposedBridge.hookAllMethods(bgView, "setDrawableAlpha", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View) || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    View v = (View) param.thisObject;
                    int want = (Integer) param.args[0];
                    // Dark mode: the vendor owns the row completely ("去掉通知卡片染色"), including the
                    // alpha it animates, so nothing is pinned.
                    boolean classic = sShadeOpen && !sNightUi;
                    if (classic && want != 255) {
                        logCardEvent("setDrawableAlpha(" + want + ") forced 255");
                        param.args[0] = 255;
                    } else if (!classic) {
                        logCardEvent("setDrawableAlpha(" + want + ") left alone ("
                                + (sNightUi ? "night" : "shade closed") + ")");
                    }
                }
            });
            log("7c hook NotificationBackgroundView.setDrawableAlpha");

            // The row's frosted layer is re-faded by the vendor's live-blur pass
            // (updateLiveBlurAlpha -> setBackgroundBlurDrawableAlpha), which runs AFTER the row has
            // been given its background and is what makes the classic fill drift back to a veil
            // (measured: 247 -> 217 on a 100px drag).  With the veil up the alpha is pinned opaque
            // here, at the source of the value.
            XposedBridge.hookAllMethods(bgView, "updateLiveBlurAlpha", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sShadeOpen || sNightUi || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    int want = (Integer) param.args[0];
                    if (want != 255) {
                        logCardEvent("updateLiveBlurAlpha(" + want + ") pinned 255");
                        param.args[0] = 255;
                    }
                }
            });
            log("7f hook NotificationBackgroundView.updateLiveBlurAlpha");

            // The vendor's card is a single BackgroundBlurDrawable that carries BOTH the frosted
            // backdrop and the fill colour, and its alpha is re-driven on every row state change
            // (drag/flick, new notification, media state) -- which is why a tint applied at
            // setBackground() time drifts back to a translucent veil (measured 247 -> 217 after a
            // 100px drag).  The classic card must not depend on a window onto the backdrop at all,
            // so while the veil is up the row paints its own opaque rounded fill and the vendor's
            // drawable is bypassed.  Its corner radii are mirrored so the shape is unchanged.
            XposedBridge.hookAllMethods(bgView, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    if (!isCardView(v)) return;
                    android.graphics.drawable.Drawable classic = classicCardFill(v);
                    if (classic != null) {
                        classic.setBounds(0, 0, v.getWidth(), v.getHeight());
                        classic.draw((android.graphics.Canvas) param.args[0]);
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.hookAllMethods(bgView, "setRadius", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    if (param.args.length < 2) return;
                    float top = ((Number) param.args[0]).floatValue();
                    float bottom = ((Number) param.args[1]).floatValue();
                    sCardRadius.put(v, new float[]{top, bottom});
                    ClassicCardFill d = sClassicCardFill.get(v);
                    if (d != null) d.setCorners(top, bottom);
                }
            });
            log("7g hook NotificationBackgroundView.onDraw / setRadius");

            Class<?> row = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.ActivatableNotificationView", cl);
            sRowClass = row;
            XposedBridge.hookAllMethods(row, "setBackground", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // the vendor just re-created the frosted drawable; put the classic fill back.
                    // Not during the module's own theme rebuild: that pass is asking for the vendor's
                    // own surface of the new palette, and the poller tints the rows right after it.
                    if (sShadeOpen && !sRowThemeRefresh) applyCardTint(true);
                }
            });
            log("7b hook ActivatableNotificationView.setBackground");
        } catch (Throwable t) {
            log("7 notification card hook FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    /** Throttled per-row event log, so one gesture cannot flood the LSPosed log. */
    private static void logCardEvent(String msg) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastCardLog < 400) return;
        sLastCardLog = now;
        log("7 event: " + msg);
    }

    /* -------- classic card fill (painted by us, bypassing the vendor's blur drawable) -------- */

    /** id/backgroundFlyme -- matched by view id so only the card's own layer is taken over. */
    private static volatile int idBackgroundFlyme = 0x7f0a0127;
    private static final Map<View, ClassicCardFill> sClassicCardFill =
            new java.util.WeakHashMap<>();
    private static final Map<View, float[]> sCardRadius = new java.util.WeakHashMap<>();

    /** True only for the notification row's own background layer. */
    private static boolean isCardView(View v) {
        return v.getId() == idBackgroundFlyme;
    }

    /**
     * The classic card surface: optionally erase what the vendor painted under the card, put the
     * cover's scrim back, then draw the calibrated translucent white.
     *
     * <p>A translucent fill paints whatever the vendor drew under the card <em>in the same window</em>
     * -- on this build its quick-toggle grid (移动热点 / 定位服务 / 魅族互传 / 飞行模式) and its media
     * tiles, which the QS panel keeps laid out below the control-centre region, so they bled through
     * the cards as soon as the QS area expanded (报告 2026-10-16/17: the blue 定位服务 tile under a
     * card read {@code #e8effe}, i.e. the card's white over the tile's {@code #376af6}).  Hiding the
     * grid only moved the problem to the next state, so the card punches its own rounded shape out of
     * the window ({@code PorterDuff.CLEAR}), lays the scrim back down at the alpha the cover currently
     * carries (so the plate is unchanged where a mask is configured), and draws the white last: the
     * only thing that can show through is the shade window's backdrop blur.
     *
     * <p><b>The punch is off for the control centre's two cards</b> ({@link #eraseUnder}, set by
     * {@link #applyQsCardFill}).  They do not need it -- their row sits above the tile grid, nothing
     * is ever laid out under them -- and it is what broke the tile-edit transition (报告: 进入/退出
     * 磁贴编辑时圆角矩形透出模糊层).  The vendor fades the whole control centre out for that
     * transition through {@code mz_expanded_qs_scroll_view.setAlpha()} (measured with a tree dump:
     * 1.00 -&gt; 0.66 at +120ms -&gt; 0.05 at +400ms).  A {@code CLEAR} op is replayed from the card's
     * cached display list and is <em>not</em> scaled by that alpha, while the scrim and the fill in
     * the same list are: the white plate stayed punched out and the card faded away from over the
     * hole, so the card's rounded rectangle became a window onto the backdrop blur.  Without the
     * punch the plate is left intact and the card fades into it -- the white mask the user expects.
     * Nothing is lost visually: the scrim is still painted at {@code 0xE0} over whatever happens to
     * be underneath, so even a tile passing under the card is attenuated to well under one level.
     */
    private static final class ClassicCardFill extends android.graphics.drawable.Drawable {
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final android.graphics.Rect last = new android.graphics.Rect();
        private final float[] radii = new float[8];
        private final android.graphics.Paint punch =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint scrim =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ink =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        /**
         * The colour of the surface, i.e. what the card reads as.
         *
         * <p>A stored field rather than a call into {@link #notificationFill()} at draw time, because
         * the same shape is also installed as the background of the control centre's two cards --
         * {@code connectivity_tiles_wrapper} and {@code slider_wrapper} -- and those carry
         * {@link #notificationFill()} in the light theme but {@link #cardColor()} at night, and a
         * shade that is still closing must not change colour under the finger.  The installer decides
         * once per state change ({@link #classicFillFor}) and the draw call only paints.
         */
        private int fill = CARD_BG_COLOR;
        /**
         * Whether the card erases what is underneath before it paints.  On for the notification rows
         * and the media card (the vendor's tiles are laid out under them), off for the control
         * centre's two cards -- see the class note.
         */
        private boolean eraseUnder = true;

        ClassicCardFill() {
            this(CARD_BG_COLOR);
        }

        ClassicCardFill(int color) {
            punch.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.CLEAR));
            setCorners(28f, 28f);
            setFill(color);
        }

        /** See {@link #eraseUnder}: the control centre's two cards paint without the punch. */
        void setEraseUnder(boolean erase) {
            eraseUnder = erase;
        }

        /**
         * The surface colour.  Never {@code 0} for the notification rows: the fill is what makes them
         * read as a card, and a fully transparent value would leave only the punch and the scrim.
         */
        void setFill(int color) {
            fill = color;
        }

        /** The colour this fill currently paints; compared by {@link #classicFillFor} before a write. */
        int getFill() {
            return fill;
        }

        /** Top / bottom corner radii, as the vendor's own {@code setRadius()} reports them. */
        void setCorners(float top, float bottom) {
            if (radii[0] == top && radii[6] == bottom) return;
            radii[0] = radii[1] = radii[2] = radii[3] = top;
            radii[4] = radii[5] = radii[6] = radii[7] = bottom;
            last.setEmpty();          // the shape changed, rebuild it on the next draw
        }

        void setCornerRadius(float r) {
            setCorners(r, r);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            if (!b.equals(last)) {
                rect.set(b);
                path.reset();
                path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW);
                last.set(b);
            }
            if (eraseUnder) canvas.drawPath(path, punch);
            int scrimColor = coverScrimColor();
            int alpha = cardScrimAlpha();
            if (alpha > 0) {
                scrim.setColor((scrimColor & 0x00FFFFFF) | (alpha << 24));
                canvas.drawPath(path, scrim);
            }
            ink.setColor(fill);
            canvas.drawPath(path, ink);
        }

        @Override
        public void setAlpha(int alpha) {
            // the fill is the module's calibrated constant, the vendor's alpha passes are ignored
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The fill the classic card is drawn with, or null when the row must keep the vendor's
     * drawable (lock screen, shade closed, transition in progress).
     */
    private static android.graphics.drawable.Drawable classicCardFill(View v) {
        // Light mode: the classic card must read as an opaque white surface lying on the cc's white
        // veil.  Dark mode: no fill at all -- "去掉通知卡片染色", the vendor's own night row
        // (setBlurBackground 0xB21A1A1A, i.e. its real blurred fill) is what the row shows.
        if (!sShadeOpen || sNightUi) return null;
        int want = notificationFill();
        // The row's corner radii are the vendor's own, reported by its setRadius() -- see hook 7.
        float[] r = sCardRadius.get(v);
        float top = r == null ? 28f : r[0];
        float bottom = r == null ? 28f : r[1];
        Integer wasBoxed = sCardFillColor.get(v);
        int was = wasBoxed == null ? Integer.MIN_VALUE : wasBoxed.intValue();
        ClassicCardFill d = classicFillFor(sClassicCardFill, v, want, top, bottom);
        sCardScrim.put(v, Integer.valueOf(cardScrimAlpha()));
        if (was != want) {
            sCardFillColor.put(v, want);
            logCardEvent("classic card fill installed 0x" + Integer.toHexString(want)
                    + " (r=" + top + "/" + bottom + ")");
        }
        return d;
    }

    /**
     * The module's own {@link ClassicCardFill} for one view, configured for {@code color} and the two
     * corner radii, created on first use.
     *
     * <p>Shared by the notification rows (which carry {@link #notificationFill()}) and the control
     * centre's two cards ({@code connectivity_tiles_wrapper} and {@code slider_wrapper}, which carry
     * {@link #notificationFill()} in the light theme and {@link #cardColor()} at night), because all
     * of them need the same layers in the same order: lay the cover's scrim back down at the alpha the
     * cover currently carries, then paint the surface.  The rows (and the media card) additionally
     * erase what the vendor painted under the shape first; the control centre's two cards do not --
     * see {@link ClassicCardFill#setEraseUnder}.  The fill drawable <em>is</em> the view's background
     * for the control centre cards and only the row's own draw layer for the rows, so the two paths
     * differ in where they are installed (see {@link #applyQsCardFill}), never in what is painted.
     *
     * <p>The stored colour is compared on every call and written through {@link
     * ClassicCardFill#setFill} when it moved, which is what makes a theme switch (or a change of the
     * fill constant) repaint the same shape instead of needing a new drawable.
     */
    private static ClassicCardFill classicFillFor(Map<View, ClassicCardFill> store, View v,
            int color, float top, float bottom) {
        ClassicCardFill d = store.get(v);
        if (d == null) {
            d = new ClassicCardFill(color);
            store.put(v, d);
        } else if (d.getFill() != color) {
            d.setFill(color);
        }
        d.setCorners(top, bottom);
        return d;
    }

    /* -------- the control centre's two cards (connectivity + sliders) -------- */

    /**
     * The background the module replaced on each control-centre card, kept so the vendor's own
     * surface can be put back exactly (shade closed, lock screen, or the dark theme -- see
     * {@link #restoreQsCard}).  Weak keys: the panel is re-inflated on every configuration change.
     */
    private static final Map<View, android.graphics.drawable.Drawable> sQsCardOrigBg =
            new java.util.WeakHashMap<>();
    /** The fill drawable the module installed on each control-centre card. */
    private static final Map<View, ClassicCardFill> sQsCardFill = new java.util.WeakHashMap<>();
    /** Which fill (colour) each control-centre card currently carries. */
    private static final Map<View, Integer> sQsCardApplied = new java.util.WeakHashMap<>();
    /** Guards {@link #applyQsCardFill}'s one-shot log line. */
    private static volatile boolean sQsCardLogged = false;

    /**
     * Give one control-centre card ({@code connectivity_tiles_wrapper} or {@code slider_wrapper}) the
     * {@link #notificationFill()} surface, or hand the vendor's own background back.
     *
     * <p><b>Why the card's background is replaced rather than its colour being overridden.</b>  Both
     * cards are cells of the control centre that the module paints white today, and the request is
     * that they use the notification card's colour ({@link #CARD_BG_COLOR}) instead of an
     * opaque one.  A plain alpha change is not enough on this build: a translucent white fill lets
     * whatever the vendor has under the card show through, which is the bleed the notification rows
     * had to solve (see {@link ClassicCardFill}).  Installing the same fill here gives the card the
     * scrim (as a stand-in for the plate the card lies on) and then the translucent white, so what
     * shows through is only what the shade's own backdrop puts there.  Half-transparent white over
     * the near-black backdrop the card sits on reads within a couple of levels of the notification
     * rows in the same frame.
     *
     * <p>The punch is deliberately <em>not</em> used on these two cards: their row sits above the
     * tile grid, nothing is ever laid out under them, and the punch is what the tile-edit transition
     * exposed as a hole onto the backdrop blur -- see {@link ClassicCardFill#setEraseUnder}.
     *
     * <p><b>Where the two backgrounds come from.</b>  {@code connectivity_tiles_wrapper} is painted by
     * the {@code drawable/qs_connectivity_wrapper_bg} shape, whose solid colour is the resource this
     * module overrides in hook 1 -- a resource a view keeps a reference to, so it cannot be recoloured
     * in place.  {@code slider_wrapper} used to be given a {@code GradientDrawable} by
     * {@link #styleSliderWrapper}; that is the drawable this replaces.  Both are re-inflated by the
     * vendor on a configuration change, which is why the poller re-applies the fill (instead of a
     * one-shot at inflation time) and why the original is read back per view.
     *
     * <p><b>Both control centres, one surface.</b>  This build inflates an {@code MzQSPanel} twice:
     * once inside the classic shade and once inside the independent panel the lock screen's pull-down
     * expands ({@code control_center_view}).  The two are separate view hierarchies with separate
     * cards, so the fill has to be installed on both and repainted on both after a theme switch --
     * see {@link #sQsCards} and {@link #repaintQsCards}.  {@code setBackground()} is a layout-changing
     * call, so the poller's per-frame path is value-guarded: the work happens only when the answer
     * changes.
     */
    private static void applyQsCardFill(View card, float radius, boolean classic) {
        if (card == null) return;
        // Dark mode keeps the night card the module already had (CARD_COLOR_NIGHT, 50% black) -- and
        // so does the tile grid, whose night value is the vendor's qs_tile_inactive_bg_color_for_night
        // and is not overridden at all.  Only the light value changes here:
        // "让它们使用 CARD_BG_COLOR 的颜色作为背景色".
        int want = sNightUi ? cardColor() : notificationFill();
        Integer applied = sQsCardApplied.get(card);
        ClassicCardFill known = sQsCardFill.get(card);
        // "ours" means this card is still carrying the module's own fill -- null must not count as a
        // match, otherwise a card the vendor left without a background would look installed.
        boolean ours = known != null && card.getBackground() == known;
        // The vendor hands these two views a drawable *instance* that was loaded when the panel was
        // inflated -- and the panel is only re-inflated on a rotation.  Handing that instance back
        // after a theme switch therefore put the other theme's surface on screen: white cards after
        // switching to dark, dark cards after switching back to light.  The fill is kept on both
        // cards at all times instead and simply follows the theme (light -> notificationFill(),
        // dark -> cardColor()), which is what the dark palette asks for anyway: cardColor() at night
        // is the vendor's own qs_tile_container_bg_color_for_night.  classic stays in the signature
        // for the call sites; the lock screen and the open shade now paint the same way.
        if (ours && applied != null && applied.intValue() == want) return;
        ClassicCardFill fill = classicFillFor(sQsCardFill, card, want, radius, radius);
        // These two cards paint without the punch: nothing is laid out under their row, and the
        // punch is what left the plate's hole visible through the tile-edit fade -- see
        // ClassicCardFill's class note.
        fill.setEraseUnder(false);
        if (!ours) {
            // First install, or the vendor re-inflated the card and put its own surface back: keep
            // that drawable (the restore path needs it) and take the surface over.
            if (!sQsCardOrigBg.containsKey(card)) sQsCardOrigBg.put(card, card.getBackground());
            card.setBackground(fill);
        } else {
            // The same drawable now carries the new colour (classicFillFor() wrote it through
            // setFill()).  setFill() cannot invalidate anything -- this Drawable has no callback on
            // the path -- so the card is redrawn by hand; without it a theme switch's new colour
            // waits for whatever the vendor invalidates next, which for the lock screen's control
            // centre is nothing at all while it is open.
            card.invalidate();
        }
        sQsCardApplied.put(card, want);
        if (!sQsCardLogged) {
            sQsCardLogged = true;
            log("6 qs card fill 0x" + Integer.toHexString(want) + " r=" + Math.round(radius)
                    + "px (connectivity + sliders)");
        }
    }

    /**
     * Hand one control-centre card back to the vendor: the background the module found on it is put
     * back verbatim.
     *
     * <p>Unlike the media card there is no vendor dispatcher to call -- one card's background is a
     * shared resource drawable and the other's was the module's own {@code GradientDrawable} -- so the
     * saved instance is restored.  {@link #sQsCardFill} deliberately stays in the map: re-installing
     * the same drawable on the next expansion must not build a new one.
     */
    private static void restoreQsCard(View card) {
        sQsCardApplied.remove(card);
        android.graphics.drawable.Drawable orig = sQsCardOrigBg.get(card);
        if (orig != null && card.getBackground() == sQsCardFill.get(card)) card.setBackground(orig);
    }

    /** Remember the exact arguments a row was painted with, so they can be replayed. */
    private static void recordCardBackground(View v, Object[] args) {
        synchronized (sCardArgs) {
            // The vendor is (re)building the row's surface -- including from our own replay.  Either
            // way the display list about to be recorded may carry the vendor's drawable again, so
            // the "this row already shows the classic fill" marker is no longer trustworthy; the
            // poller re-invalidates until onDraw has put the fill back (see applyCardTint).  This is
            // the slow-pull case: the module's white is visible first, then the vendor re-applies
            // its frosted colour and the card changes under the finger.
            sCardFillColor.remove(v);
            sCardScrim.remove(v);
            Object[] old = sCardArgs.get(v);
            // First sight only -- except during a theme rebuild, where the vendor's new call is the
            // authoritative "own surface" of the palette that is active now (see refreshNotificationRowTheme).
            if (old != null && !sRowThemeRefresh) return;
            if (old == null) {
                sCardViews.add(new java.lang.ref.WeakReference<>(v));
                if (sCardArgs.size() <= 3) {
                    log("7 card recorded: color=0x" + Integer.toHexString((Integer) args[1])
                            + " static=" + args[0] + " clipRadius=" + args[2]);
                }
            }
            sCardArgs.put(v, args.clone());
            sCardArgsNight.put(v, Boolean.valueOf(sNightUi));
            sCardApplied.remove(v);
        }
    }

    /**
     * The notification row that owns one {@code backgroundFlyme} layer.
     *
     * <p>{@link #sCardArgs} is keyed by the {@code NotificationBackgroundView} (the layer the vendor
     * hands its colour to), while the method that recomputes that colour for the current theme
     * ({@code ActivatableNotificationView#setBackground()}) lives on the row above it.
     */
    private static View rowOwner(View bg) {
        Class<?> row = sRowClass;
        if (row == null) return null;
        android.view.ViewParent p = bg.getParent();
        for (int i = 0; i < 5 && p instanceof View; i++) {
            View v = (View) p;
            if (row.isInstance(v)) return v;
            p = v.getParent();
        }
        return null;
    }

    /**
     * Rebuild every tracked notification row's surface in the palette that is active now.
     *
     * <p>Why this is needed at all: the row's colour is <em>recorded</em> from the vendor's own
     * {@code setBlurBackground()} call (see {@link #sCardArgs}) and replayed by {@link #applyCardTint}
     * whenever the row has to go back to "the vendor's own surface" (lock screen, shade closed).  The
     * record is written once, and a day/night switch does not rewrite it -- so after dark -&gt; light
     * the replay still carried {@code 0xb21a1a1a} and the lock screen's cards came back dark
     * (报告 2026-10-17: 锁屏通知卡片 深色转浅色之后颜色不对).  The row does have a dispatcher that
     * recomputes the colour from the theme -- {@code ActivatableNotificationView#setBackground()},
     * the same one the vendor calls when a row is attached -- so it is called here, once per switch,
     * and the record is refreshed by the hook.
     */
    private static void refreshNotificationRowTheme() {
        java.util.List<View> owners = new java.util.ArrayList<>();
        synchronized (sCardArgs) {
            for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = sCardViews.iterator();
                    it.hasNext(); ) {
                View bg = it.next().get();
                if (bg == null) {
                    it.remove();
                    continue;
                }
                if (!bg.isAttachedToWindow()) continue;
                View owner = rowOwner(bg);
                if (owner != null && !owners.contains(owner)) owners.add(owner);
            }
        }
        if (owners.isEmpty()) return;
        sRowThemeRefresh = true;
        int ok = 0;
        try {
            for (View owner : owners) {
                try {
                    XposedHelpers.callMethod(owner, "setBackground");
                    ok++;
                } catch (Throwable t) {
                    if (!sRowRefreshFailed) {
                        sRowRefreshFailed = true;
                        log("7 row theme refresh FAILED: " + t);
                    }
                }
            }
        } finally {
            sRowThemeRefresh = false;
        }
        log("7 notification rows rebuilt for night=" + sNightUi + " (" + ok + "/" + owners.size() + ")");
    }

    /** Add one view to a weak list, identity-deduped, pruning the collected refs on the way. */
    private static void rememberView(java.util.List<java.lang.ref.WeakReference<View>> list, View v) {
        if (v == null) return;
        synchronized (list) {
            for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = list.iterator();
                    it.hasNext(); ) {
                View known = it.next().get();
                if (known == null) {
                    it.remove();
                } else if (known == v) {
                    return;
                }
            }
            list.add(new java.lang.ref.WeakReference<>(v));
        }
    }

    /** Live, attached views of a weak list (prunes the dead entries); empty list when none. */
    private static java.util.List<View> liveViews(
            java.util.List<java.lang.ref.WeakReference<View>> list, boolean attachedOnly) {
        java.util.List<View> out = new java.util.ArrayList<>();
        synchronized (list) {
            for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = list.iterator();
                    it.hasNext(); ) {
                View v = it.next().get();
                if (v == null) {
                    it.remove();
                } else if (!attachedOnly || v.isAttachedToWindow()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static int tileCtorDepth() {
        Integer d = sTileCtorDepth.get();
        return d == null ? 0 : d.intValue();
    }

    private static void pushTileCtor() {
        sTileCtorDepth.set(Integer.valueOf(tileCtorDepth() + 1));
    }

    private static void popTileCtor() {
        int depth = tileCtorDepth() - 1;
        if (depth <= 0) sTileCtorDepth.remove();
        else sTileCtorDepth.set(Integer.valueOf(depth));
    }

    /**
     * Re-run the vendor's own colour passes after a day/night switch.
     *
     * <p>Nothing in SystemUI re-inks the panel on a uiMode change: the header is only recoloured from
     * {@code onDarkChanged} / {@code onShadeExpandedChanged} / {@code onKeyguardVisibilityChanged},
     * and the tile views of the connectivity card are not part of the grid the vendor re-runs
     * {@code updateResources()} on.  So after dark -&gt; light the clock, the "收纳箱" button and the
     * labels inside the connectivity card kept the night ink and disappeared on the white card
     * (报告 2026-10-17: 时间 / 收纳盒按钮 / 卡片内的字 变白看不清).  Both passes are the vendor's own;
     * the module only triggers them:
     * <ul>
     *   <li>{@code StatusBarHeaderView#onShadeExpandedChanged(true)} -- the same call the vendor makes
     *       when the shade is pulled down, and the one the module's {@code updateViewColor} hook turns
     *       into the dark ink in light mode;</li>
     *   <li>{@code QSTileViewImpl#updateResources()} -- the night-change animation that re-applies the
     *       label / secondary-label / icon / background colours of one tile.</li>
     * </ul>
     */
    private static void reInkForTheme() {
        int headers = 0;
        int tiles = 0;
        Object dispatcher = darkIconDispatcher();
        for (View header : liveViews(sHeaderViews, true)) {
            try {
                if (dispatcher == null) break;
                // The vendor's own re-tint of one receiver: onDarkChanged() -> updateViewColor(),
                // which is the method the module's hook forces to the dark ink in light mode.
                XposedHelpers.callMethod(dispatcher, "applyDark", header);
                headers++;
            } catch (Throwable t) {
                logOnceReInk("header", t);
            }
        }
        for (View tile : liveViews(sTileViews, true)) {
            try {
                XposedHelpers.callMethod(tile, "updateResources");
                tiles++;
            } catch (Throwable t) {
                logOnceReInk("tile", t);
            }
        }
        log("re-ink for night=" + sNightUi + ": headers=" + headers + " tiles=" + tiles);
    }

    /** {@code Dependency.get(DarkIconDispatcher.class)} -- the status-bar ink dispatcher. */
    private static Object darkIconDispatcher() {
        Object d = sDarkDispatcher;
        if (d != null) return d;
        try {
            ClassLoader cl = sClassLoader;
            Class<?> dep = XposedHelpers.findClass("com.android.systemui.Dependency", cl);
            Class<?> iface = XposedHelpers.findClass(
                    "com.android.systemui.plugins.DarkIconDispatcher", cl);
            d = XposedHelpers.callStaticMethod(dep, "get", iface);
            sDarkDispatcher = d;
        } catch (Throwable t) {
            logOnceReInk("dispatcher", t);
        }
        return d;
    }

    private static volatile Object sDarkDispatcher;
    private static volatile boolean sReInkFailed = false;

    private static void logOnceReInk(String what, Throwable t) {
        if (sReInkFailed) return;
        sReInkFailed = true;
        log("re-ink " + what + " FAILED: " + t);
        XposedBridge.log(t);
    }

    /**
     * One-shot diagnostic kept in so a future regression can be told apart from a theme change in
     * a single log line.
     */
    private static void logCardState(View v, int color) {
        long now = android.os.SystemClock.uptimeMillis();
        if (sCardLogged && now - sLastCardLog < 2000) return;
        sCardLogged = true;
        sLastCardLog = now;
        try {
            android.graphics.drawable.Drawable d = v.getBackground();
            int panelAlpha = -1;
            try {
                Object cc = XposedHelpers.getObjectField(v, "mCenterController");
                if (cc != null) {
                    Object a = XposedHelpers.callMethod(cc, "getPanelAlpha");
                    if (a instanceof Number) panelAlpha = ((Number) a).intValue();
                }
            } catch (Throwable ignored) {
            }
            StringBuilder chain = new StringBuilder();
            View p = v;
            for (int i = 0; p != null && i < 8; i++) {
                chain.append(String.format("%.2f", p.getAlpha())).append('<');
                android.view.ViewParent vp = p.getParent();
                p = (vp instanceof View) ? (View) p : null;
            }
            log("7 card state: arg=0x" + Integer.toHexString(color)
                    + " bg=" + (d == null ? "null" : d.getClass().getSimpleName())
                    + " bgAlpha=" + (d == null ? -1 : d.getAlpha())
                    + " panelAlpha=" + panelAlpha + " alphaChain=" + chain);
        } catch (Throwable t) {
            log("7 card state FAILED: " + t);
        }
    }

    /**
     * Replay {@code setBlurBackground()} on every recorded card with the classic fill, and make sure
     * the row actually re-records the frame that carries it.
     *
     * <p>Value-guarded per view: the poller runs at 60fps while the shade is moving and must not
     * make the rows re-render every frame.  The replay itself is guarded by {@link #sCardApplied},
     * but that is <em>not</em> enough on this build, because the row's surface is drawn by
     * {@code NotificationBackgroundView.onDraw()} into the view's hardware display list and a
     * recorded list keeps the values it was recorded with until the view is invalidated again.
     * Two different things go stale during a pull:
     *
     * <ol>
     *   <li><b>The fill itself.</b>  A list recorded before the veil came up contains the vendor's
     *       {@code 0xB2FFFFFF} draw op.  Replaying {@code setBlurBackground(0xDDFFFFFF)} only
     *       swaps the {@code mBackground} object; with no invalidation the list keeps painting the
     *       old op, so the card stays a 70%-white blur veil.  {@link #sCardFillColor} is written by
     *       {@link #classicCardFill} from inside {@code onDraw} (i.e. only once the list really was
     *       recorded with the fill), is dropped on close, and is also dropped by
     *       {@link #recordCardBackground} whenever the vendor re-applies its own surface.</li>
     *   <li><b>The scrim alpha.</b>  {@link ClassicCardFill#draw} lays the cover's scrim back down
     *       inside the card before the translucent white, so its alpha is the cover's alpha at
     *       record time.  During a pull the cover ramps 0.01 -&gt; 1.0; a list recorded at 0.29
     *       (scrim 65) keeps a thin scrim after the pull has settled at 1.0 (scrim 224), and the
     *       {@code 0xDD} white then lets the card's own blur sample through -- exactly the reported
     *       "you can see it apply the module colour first, then change colour".  {@link
     *       #sCardScrim} tracks the value each row was last painted with and every change
     *       invalidates that row again.</li>
     * </ol>
     *
     * <p>So the poller pairs the replay with an {@code invalidate()} for every visible row whose
     * list is missing the fill or was recorded with a different scrim alpha.  It costs at most a
     * few frames per open and stops as soon as the list matches the current state.
     *
     * <p>On the way out the marker is dropped for every row, so the next open starts the same forced
     * repaint even though the colour argument has not changed in between.
     */
    private static void applyCardTint(boolean white) {
        // Dark mode: no tinting of any kind -- replaying the recorded arguments with their original
        // colour is exactly "去掉通知卡片染色": the row keeps whatever the vendor painted.
        if (white && sNightUi) white = false;
        int scrimAlpha = white ? cardScrimAlpha() : 0;
        int tracked = 0;
        int attached = 0;
        int replayed = 0;
        int redrawn = 0;
        synchronized (sCardArgs) {
            java.util.Iterator<java.lang.ref.WeakReference<View>> it = sCardViews.iterator();
            while (it.hasNext()) {
                View v = it.next().get();
                if (v == null) {
                    it.remove();
                    continue;
                }
                Object[] orig = sCardArgs.get(v);
                if (orig == null || orig.length < 2 || !(orig[1] instanceof Integer)) continue;
                tracked++;
                if (!v.isAttachedToWindow()) continue;
                attached++;
                if (!white) {
                    // The shade is closing / closed: whatever list this row keeps is the vendor's
                    // surface, so the next open must repaint it again (see the method note).
                    sCardFillColor.remove(v);
                    sCardScrim.remove(v);
                }
                int want;
                if (white) {
                    want = notificationFill();
                } else {
                    Boolean recNight = sCardArgsNight.get(v);
                    if (recNight != null && recNight.booleanValue() != sNightUi) {
                        // The record belongs to the palette of the other theme, and the vendor only
                        // writes it on first sight -- replaying it is exactly what forced the lock
                        // screen's rows back to the dark surface after dark -> light (报告 2026-10-17:
                        // 锁屏通知卡片 在深色转浅色之后颜色不对).  The poller rebuilds the rows on the
                        // switch (refreshNotificationRowTheme); this is the safety net for a row that
                        // could not be rebuilt, so the vendor's own surface is left in place.
                        continue;
                    }
                    want = (Integer) orig[1];
                }
                Integer applied = sCardApplied.get(v);
                if (applied == null || applied.intValue() != want) {
                    Object[] args = orig.clone();
                    args[1] = want;
                    try {
                        XposedHelpers.callMethod(v, "setBlurBackground", args);
                        if (white) {
                            // setBlurBackground() only pushes the colour into the blur drawable; the
                            // drawable's own alpha is whatever the vendor's last animation frame left
                            // behind (a swipe over a row re-drives it).  Writing it here, after the
                            // replay, is what makes the classic fill identical every time.
                            if (v.getBackground() != null) v.getBackground().setAlpha(255);
                            XposedHelpers.callMethod(v, "setDrawableAlpha", 255);
                        }
                        sCardApplied.put(v, want);
                        replayed++;
                    } catch (Throwable t) {
                        if (!sCardReplayFailed) {
                            sCardReplayFailed = true;
                            log("7 card replay FAILED: " + t);
                            XposedBridge.log(t);
                        }
                    }
                }
                if (white) {
                    // classicCardFill() writes this marker when onDraw installs the fill, so an
                    // entry that is missing/other is a row whose display list still carries the
                    // vendor's surface.  Invalidate until it re-records -- see the method note.
                    Integer drawn = sCardFillColor.get(v);
                    Integer drawnScrim = sCardScrim.get(v);
                    boolean scrimStale = drawnScrim == null
                            || drawnScrim.intValue() != scrimAlpha;
                    if ((drawn == null || drawn.intValue() != want || scrimStale)
                            && v.isShown()) {
                        v.invalidate();
                        redrawn++;
                    }
                }
            }
        }
        long now = android.os.SystemClock.uptimeMillis();
        if ((replayed > 0 || redrawn > 0) && now - sLastTintLog > 3000) {
            sLastTintLog = now;
            log("7 tint white=" + white + " replayed=" + replayed + "/" + attached
                    + " repainted=" + redrawn);
        }
    }

    /* ================================================================== */
    /*  Hook 7m: the media player card (media carousel)                    */
    /* ================================================================== */

    /**
     * Hook 7m -- the media player card in the shade.
     *
     * <p>The card between the control centre and the notification list is the one surface of the
     * classic panel that hook 7 cannot reach: it is not drawn by a {@code NotificationBackgroundView}
     * but by {@code com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout}
     * (id {@code qs_media_controls}, the root of {@code res/layout/media_session_view.xml}), and the
     * vendor builds its whole surface in that class -- so the module's row fill never applied to it
     * and it kept the vendor's frosted look next to the white rows.
     *
     * <p>What the vendor does there (decompiled from this build's SystemUI, Flyme 12.3.0.0A):
     * <pre>
     *   setBackground()                       // the dispatcher, called on inflate / attach /
     *     if (isSupportBlur())                // config change / media or live-notification change
     *       if (live &amp;&amp; showingLiveNoti &amp;&amp; finished) setBlurBgForLive(); else setBlurBgForStatic();
     *     else { setBackground(mNormalBg); mNormalBg.setTint(media_bg_color); }
     *   setBlurBgForLive()   MzBlurUtils.setBackgroundBlurDrawable(this, alpha, radius=180f, ...,
     *                          night ? 0xB21A1A1A : 0xB2FFFFFF, ...)
     *   setBlurBgForStatic() WallpaperBlurDrawableManager.addBlurDrawableTo(...,
     *                          night ? 0xB21A1A1A : 0xB2FFFFFF, mClipCornerRadius)
     * </pre>
     * Both blur paths fill the card with the same {@code 0xB2FFFFFF} (70% white) that hook 7 takes
     * over on the rows, and their alpha is re-driven by {@code setBackgroundBlurAlpha()} /
     * {@code onFlymeAlphaChanged()} while the shade moves -- the same drift the rows show.
     *
     * <p>Measured on the device (1368x3192, light theme, shade fully open, portrait):
     * <pre>
     *   notification row, module fill     #fafafa   (250)
     *   media card, vendor fill           #bbbcbd .. #c9cacc (188 .. 202)
     * </pre>
     * and {@code dumpsys SurfaceFlinger} lists the card as a second blur region on the shade window
     * ({@code radius=180u, cornerRadii=[67,76,73,80], rect=[70,1386,1298,2044]} -- exactly the
     * {@code qs_media_controls} bounds from {@code uiautomator}), i.e. the frosted fill is live.
     *
     * <p>The card is then taken over exactly like a row: while the classic veil is up its fill
     * becomes the module's {@link #notificationFill()} in the card's own {@code mClipCornerRadius}
     * shape, and the vendor's blurred-artwork layer ({@code id/media_mask_bg}, an
     * {@code ImageFilterView} that carries the media-wallpaper artwork) is emptied so it cannot paint
     * over the white.  Recolouring the vendor's blur drawable instead of replacing it was measured
     * on the device and does not reach the rows' surface (235..237 against their 250); the numbers
     * and the reason are in {@link #applyMediaCardTint}.
     *
     * <p>Dark mode is left alone ("去掉通知卡片染色"): the vendor's own frosted night card
     * ({@code 0xB21A1A1A}) is what the card shows, and the fill is released again as soon as the
     * shade closes or the theme flips.
     */
    private static void hookMediaCardTint(ClassLoader cl) {
        try {
            Class<?> card = XposedHelpers.findClass(
                    "com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout", cl);
            // Exactly the three declared no-arg methods that (re)build the surface.  findMethodExact
            // is used on purpose: hookAllMethods would also collect the inherited
            // View#setBackground(Drawable) and, through it, fire on every view in the process.
            hookMediaCardBuilder(card, "setBackground");
            hookMediaCardBuilder(card, "setBlurBgForLive");
            hookMediaCardBuilder(card, "setBlurBgForStatic");
            log("7m hook MediaCarouseTransitionLayout.setBackground / setBlurBgForLive / setBlurBgForStatic");
        } catch (Throwable t) {
            log("7m media card hook FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    private static void hookMediaCardBuilder(Class<?> card, final String name) {
        try {
            Method m = XposedHelpers.findMethodExact(card, name);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        applyMediaCardTint((View) param.thisObject, sShadeOpen);
                    }
                }
            });
        } catch (Throwable t) {
            log("7m no " + name + " on the media card: " + t);
        }
    }

    /**
     * Give the media card the classic fill, or hand it back to the vendor.
     *
     * <p>{@code classic} is the caller's "the veil is up" state (the poller passes the same
     * {@code open} flag it passes to {@link #applyCardTint}), and dark mode always wins over it, as
     * it does for the rows.  The call is idempotent and cheap enough for the 60fps poller: it
     * compares the view's background against the module's own drawable and the mask against "no
     * artwork" before touching anything.
     *
     * <p><b>Why the surface is replaced instead of the blur drawable being recoloured.</b>  Handing
     * the vendor's {@code 0xB2FFFFFF} over to {@link #notificationFill()} on its own -- through the
     * same {@code BackgroundBlurDrawable#setColor(int)} the vendor's {@code MzBlurUtils} uses, with
     * the drawable's alpha pinned the way hook 7 pins the rows' -- was measured on the device and is
     * <em>not</em> enough: the card came out {@code #ebebeb..#ecede} (235..237) with the backdrop's
     * blotches still coming through it, against the rows' {@code #fafafa} (250) in the same frame,
     * because that drawable keeps sampling the window behind the shade -- the app, dimmed -- while a
     * row already sits on the module's own veil.  Painting the fill here instead (rounded by the
     * vendor's own {@code mClipCornerRadius}) lands on the same surface as a row (measured 251/250)
     * and drops the card's 180px blur region with the drawable.
     */
    private static void applyMediaCardTint(View card, boolean classic) {
        if (card == null || sMediaFilling) return;
        boolean want = classic && !sNightUi;
        ClassicCardFill fill = sMediaCardFill.get(card);
        boolean ours = fill != null && card.getBackground() == fill;
        if (!want) {
            if (ours || sMediaMaskArt.get(card) != null) restoreMediaCard(card);
            return;
        }
        android.widget.ImageView mask = mediaMask(card);
        boolean maskClear = mask == null || mask.getDrawable() == null;
        Integer applied = sMediaCardApplied.get(card);
        if (ours && maskClear && applied != null && applied.intValue() == notificationFill()) return;
        sMediaFilling = true;
        try {
            if (fill == null) {
                fill = new ClassicCardFill();
                sMediaCardFill.put(card, fill);
            }
            fill.setCornerRadius(mediaCardRadiusPx(card));
            card.setBackground(fill);
            if (mask != null) {
                // The card's second vendor layer: the media-wallpaper artwork set by
                // MediaControlPanel#notifyMediaWallpaperBackgroundSet().  The drawable is kept aside
                // rather than dropped, so the vendor's own artwork can be put back when the tint is
                // released; it is taken off the view, never hidden by alpha -- alpha is the vendor's
                // animated property and is left exactly as it was.
                android.graphics.drawable.Drawable art = mask.getDrawable();
                if (art != null) {
                    sMediaMaskArt.put(card, art);
                    mask.setImageDrawable(null);
                }
            }
            sMediaCardApplied.put(card, notificationFill());
            if (!sMediaCardLogged) {
                sMediaCardLogged = true;
                log("7m media card fill 0x" + Integer.toHexString(notificationFill())
                        + " (r=" + mediaCardRadiusPx(card) + "px)");
            }
        } catch (Throwable t) {
            log("7m media card fill FAILED: " + t);
            XposedBridge.log(t);
        } finally {
            sMediaFilling = false;
        }
    }

    /**
     * Release the card back to the vendor: its own surface is rebuilt by calling the vendor's
     * dispatcher (which re-creates the blur drawable with the vendor's own fill colour and re-runs
     * its alpha logic), and any artwork that was lifted off the mask is put back if the vendor has
     * not replaced it in the meantime.
     */
    private static void restoreMediaCard(View card) {
        sMediaCardApplied.remove(card);
        android.widget.ImageView mask = mediaMask(card);
        android.graphics.drawable.Drawable art = sMediaMaskArt.remove(card);
        if (art != null && mask != null && mask.getDrawable() == null) {
            mask.setImageDrawable(art);
        }
        if (card.getBackground() != sMediaCardFill.get(card)) return;
        sMediaFilling = true;
        try {
            // the vendor's own dispatcher: picks the blur variant again, or mNormalBg + media_bg_color
            XposedHelpers.callMethod(card, "setBackground");
        } catch (Throwable t) {
            log("7m media card restore FAILED: " + t);
        } finally {
            sMediaFilling = false;
        }
    }

    /** The card's blurred-artwork layer ({@code id/media_mask_bg}), if the layout has one. */
    private static android.widget.ImageView mediaMask(View card) {
        View v = card.findViewById(idMediaMaskBg);
        return v instanceof android.widget.ImageView ? (android.widget.ImageView) v : null;
    }

    /** The corner radius the vendor gave the card ({@code mClipCornerRadius}, 14dp). */
    private static float mediaCardRadiusPx(View card) {
        try {
            Object r = XposedHelpers.getObjectField(card, "mClipCornerRadius");
            if (r instanceof Number) return ((Number) r).floatValue();
        } catch (Throwable ignored) {
        }
        return dimenDp(card.getResources(), "notification_background_radius", 14f)
                * card.getResources().getDisplayMetrics().density;
    }

    /**
     * The media card in the shade, cached -- it is inflated once and lives as long as the shade, so
     * the poller must not walk the tree for it every frame.  Re-found when it is detached (the carousel
     * is re-inflated on a configuration change).
     */
    private static View mediaCard(ViewGroup root) {
        View v = sMediaCard.get();
        if (v != null && v.isAttachedToWindow()) return v;
        if (root == null || idMediaControls == 0) return null;
        v = root.findViewById(idMediaControls);
        if (v != null) sMediaCard = new java.lang.ref.WeakReference<>(v);
        return v;
    }

    /** True once the shift has been reported, for logging only. */
    private static volatile boolean sShiftApplied = false;

    private static volatile boolean sShiftLogged = false;

    /**
     * Track the header views and the tile views, so {@link #reInkForTheme} can re-run the vendor's own
     * colour pass on a day/night switch (see that method for why nothing else does it).
     *
     * <p>The tile hook also marks "a tile is being constructed" for the resource funnel: the
     * constructor caches its colours in final fields as a light/night pair, so it must see the light
     * values even while the system is dark.
     */
    private static void hookThemeAwareViews(ClassLoader cl, Class<?> headerCls) {
        try {
            XposedBridge.hookAllConstructors(headerCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        rememberView(sHeaderViews, (View) param.thisObject);
                    }
                }
            });
            log("4a hook StatusBarHeaderView constructors");
        } catch (Throwable t) {
            log("4a header tracking FAILED: " + t);
        }
        try {
            Class<?> tile = XposedHelpers.findClass(
                    "com.android.systemui.qs.tileimpl.QSTileViewImpl", cl);
            XposedBridge.hookAllConstructors(tile, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    pushTileCtor();
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    popTileCtor();
                    if (param.thisObject instanceof View) {
                        rememberView(sTileViews, (View) param.thisObject);
                    }
                }
            });
            log("7t hook QSTileViewImpl constructors");
        } catch (Throwable t) {
            log("7t tile view tracking FAILED: " + t);
        }
    }

    private static void hookViewColor(String className, ClassLoader cl, String label) {
        Class<?> c = XposedHelpers.findClass(className, cl);
        XposedBridge.hookAllMethods(c, "updateViewColor", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // Forced unconditionally: the system reads the wallpaper rather than our cover, so it
                // asks for WHITE while the light cover is up -- and it does not reliably call this
                // again when the shade opens, so a state-dependent gate never fired.  Dark mode asks
                // for the opposite: leave the vendor's automatic colour alone (autoInk()).
                if (!FORCE_DARK_HEADER || autoInk()) return;
                if (param.args.length == 1 && param.args[0] instanceof Integer) {
                    param.args[0] = DARK_TINT;
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // updateViewColor tints the collapse/filter icons itself, but the clock can also be
                // recoloured elsewhere -- keep the icons on whatever the clock ended up as, so they
                // can never disagree (the method also caches and returns early on a repeat colour).
                syncHeaderIcons(param.thisObject);
            }
        });
        log("hook " + label);
    }

    /** Copy the clock's current colour onto the header's icon buttons. */
    private static void syncHeaderIcons(Object header) {
        try {
            Object time = XposedHelpers.getObjectField(header, "mTime");
            if (!(time instanceof android.widget.TextView)) return;
            int color = ((android.widget.TextView) time).getCurrentTextColor();
            String[] fields = {"mQSNotificationFilterBtn", "mQSEditNotiFilterBtn",
                    "mQSTilesEditButton", "mQsTilesSettingButton", "mQSEditDoneBt"};
            for (String f : fields) {
                Object b;
                try {
                    b = XposedHelpers.getObjectField(header, f);
                } catch (Throwable ignored) {
                    continue;
                }
                if (b instanceof android.widget.ImageButton) {
                    android.widget.ImageButton ib = (android.widget.ImageButton) b;
                    ib.setColorFilter(color);
                    // a colour filter is ignored once a tint list is set on the drawable, which is
                    // why the collapse button stayed white while the clock went dark
                    ib.setImageTintList(android.content.res.ColorStateList.valueOf(color));
                }
            }
        } catch (Throwable ignored) {
        }
    }


    /**
     * Hook 8 -- the "intercepted notifications" box (the inbox button in the shade header).
     *
     * <p>The panel is inflated from {@code res/layout/notification_filter_panel.xml}, whose title,
     * empty hint and close glyph are all designed for a dark backdrop -- the two text views carry
     * {@code android:textColor="@android:color/white"}, and the close glyph is a white PNG
     * ({@code ic_exit_filter_panel}) with no tint of its own.  The vendor's own colour pass does not
     * save them either: {@code NotificationFilterPanel#updateColors(int)} writes white whenever the
     * argument is 0 <em>or</em> {@code KeyguardUpdateMonitor.usedDefaultColor()} is true -- and the
     * latter is exactly the state this module creates, because the platform still believes the
     * lock-screen wallpaper is the backdrop although a white cover is painted over the shade
     * (see hook 4).
     *
     * <p>So both text views are forced dark after every pass that can rewrite them:
     * {@code updateColors}, {@code updateHeaderMarginTop}, {@code onFinishInflate} (first chance,
     * before the panel is ever shown) and {@code onConfigurationChanged} (rotation rebuilds the
     * header).
     *
     * <p>THE CLOSE GLYPH IS A SEPARATE, NASTIER CASE, and it is why this hook also forces
     * {@code CornerAlphaOptimizedImageButton#changeFilterColor}.  That button carries its own
     * recolour funnel: its {@code KeyguardUpdateMonitorCallback.onLockWallpaperColorChanged} calls
     * {@code changeFilterColor(AutoColorUtil.getCurrentColor(true, 2))}, which is <em>white</em> here
     * for the same reason {@code updateColors} is, and {@code changeFilterColor(int)} is
     * {@code setColorFilter(colour, MULTIPLY)} on the ImageView.
     *
     * <p>That last detail decides the whole thing: an ImageView colour filter lands in the
     * {@code BitmapDrawable}'s paint, and {@code BitmapDrawable.draw()} only consults its own tint
     * filter while the paint has <em>no</em> colour filter
     * ({@code if (mTintFilter != null &amp;&amp; paint.getColorFilter() == null)}).  A tint list set by
     * this module is therefore dead the moment the vendor writes its own filter -- and it writes it
     * <em>after</em> us, from the wallpaper-colour callback, which is not a callback this hook can
     * see.  Measured: at the first open after a fresh SystemUI the glyph stayed white; one
     * landscape round-trip made it black, because {@code onConfigurationChanged} re-ran
     * {@code updateHeaderMarginTop} (new drawable) and then this hook, i.e. the black filter was
     * written last for once.
     *
     * <p>Forcing the argument at the vendor's own funnel fixes it at the source: MULTIPLY of the
     * white glyph with a dark colour is that dark colour, so no tint list is needed at all.  The
     * direct {@code setColorFilter}/{@code setImageTintList} pair is kept as well, so the glyph is
     * already dark on the frames between the inflate/rotation pass and the vendor's next recolour.
     */
    private static void hookFilterPanel(ClassLoader cl) {
        try {
            Class<?> panel = XposedHelpers.findClass(
                    "com.flyme.systemui.statusbar.notification.NotificationFilterPanel", cl);
            XC_MethodHook fix = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyFilterPanelColors(param.thisObject);
                }
            };
            XposedBridge.hookAllMethods(panel, "updateColors", fix);
            XposedBridge.hookAllMethods(panel, "updateHeaderMarginTop", fix);
            XposedBridge.hookAllMethods(panel, "onFinishInflate", fix);
            XposedBridge.hookAllMethods(panel, "onConfigurationChanged", fix);
            hookFilterCloseButtonColor(cl);
            log("8 hook NotificationFilterPanel colours");
        } catch (Throwable t) {
            log("8 NotificationFilterPanel not found");
            XposedBridge.log(t);
        }
    }

    /**
     * The close glyph's own recolour funnel -- see {@link #hookFilterPanel}.  Only the filter box's
     * button is forced: the same class backs the header's inbox button, whose colour the header
     * pass (hook 4) already owns, and the tile-edit buttons, which are outside this module's scope.
     */
    private static void hookFilterCloseButtonColor(ClassLoader cl) {
        Class<?> btn = XposedHelpers.findClass(
                "com.flyme.systemui.statusbar.notification.CornerAlphaOptimizedImageButton", cl);
        XposedBridge.hookAllMethods(btn, "changeFilterColor", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                if (!isFilterCloseButton(param.thisObject)) return;
                // Written for both themes: the vendor's own pass asks for white here whatever the
                // theme is (it reads the lock-screen wallpaper), which is why the glyph used to stay
                // white on the light plate; in dark mode white is right, so only the light case needs
                // to be overridden -- see applyFilterPanelColors.
                param.args[0] = sNightUi ? LIGHT_TINT : DARK_TINT;
            }
        });
        log("8b hook CornerAlphaOptimizedImageButton.changeFilterColor");
    }

    /**
     * True for the filter box's own close glyph.  The view id is the primary signal (it survives our
     * hook missing the instance); the reference captured by {@link #applyFilterPanelColors} is the
     * fallback for the case where the id lookup failed on a rebuilt APK.
     */
    private static boolean isFilterCloseButton(Object o) {
        if (!(o instanceof View)) return false;
        View v = (View) o;
        if (v == sFilterCloseBtn) return true;
        return idCloseFilterPanel != 0 && v.getId() == idCloseFilterPanel;
    }

    /**
     * Classic ink for the intercepted-notification box: title, empty hint and the close glyph.
     *
     * <p>Unlike the shade header (which the vendor repaints itself, so {@link #autoInk} leaves it
     * alone in dark mode), this panel keeps whatever colour it was last given: its own colour pass
     * only runs on {@code updateColors} / rotation, not on a theme switch, and the view is reused.
     * Measured: after a light -> dark switch without a SystemUI restart the title stayed
     * {@code #191919} on the dark backdrop (the module had forced it dark while the light plate was
     * up).  So the ink is written for both themes here -- dark ink on the light plate, the old
     * design's white in dark mode -- and {@link #applyCoverAlpha} re-applies it when the theme flips.
     */
    private static void applyFilterPanelColors(Object panel) {
        try {
            final int ink = sNightUi ? LIGHT_TINT : DARK_TINT;
            final int text = sNightUi ? LIGHT_TINT : FILTER_PANEL_TEXT_COLOR;
            sFilterPanel = panel;
            sFilterPanelNight = sNightUi;
            int headerColor = -1;
            int tipColor = -1;
            Object header = XposedHelpers.getObjectField(panel, "mFilterHeader");
            if (header instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) header;
                tv.setTextColor(text);
                headerColor = tv.getCurrentTextColor();
            }
            Object tip = XposedHelpers.getObjectField(panel, "mFilterEmptyTip");
            if (tip instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) tip;
                tv.setTextColor(text);
                tipColor = tv.getCurrentTextColor();
            }
            Object close = XposedHelpers.getObjectField(panel, "mIvCloseFilterPanel");
            if (close instanceof android.widget.ImageView) {
                android.widget.ImageView iv = (android.widget.ImageView) close;
                sFilterCloseBtn = iv;
                // The colour filter is the one the BitmapDrawable actually honours (it outranks the
                // tint list, see hookFilterPanel), so it is written last and written again by the
                // changeFilterColor hook whenever the vendor recolours the glyph.
                iv.setColorFilter(ink);
                iv.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
            }
            if (!sFilterPanelLogged) {
                sFilterPanelLogged = true;
                log("8 filter panel colours forced 0x"
                        + Integer.toHexString(headerColor) + " tip=0x"
                        + Integer.toHexString(tipColor) + " close=0x"
                        + Integer.toHexString(ink));
            }
        } catch (Throwable t) {
            log("8 applyFilterPanelColors FAILED");
            XposedBridge.log(t);
        }
    }

    private static volatile boolean sFilterPanelLogged = false;
    /** The live {@code close_filter_panel} view; see {@link #isFilterCloseButton}. */
    private static volatile android.widget.ImageView sFilterCloseBtn;
    /** The filter panel itself, so a theme switch can repaint it (see applyFilterPanelColors). */
    private static volatile Object sFilterPanel;
    /** Which palette the filter panel was last painted for. */
    private static volatile boolean sFilterPanelNight = false;

    /** Hook 13 -- dark "暂无通知" ({@code empty_shade_text}) on the white plate; white in dark mode. */
    private static void hookEmptyShadeView(ClassLoader cl) {
        try {
            Class<?> empty = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.EmptyShadeView", cl);
            XC_MethodHook fix = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyEmptyShadeInk(param.thisObject);
                }
            };
            XposedBridge.hookAllMethods(empty, "updateColor", fix);
            XposedBridge.hookAllMethods(empty, "onFinishInflate", fix);
            log("13 hook EmptyShadeView ink (empty_shade_text)");
        } catch (Throwable t) {
            log("13 EmptyShadeView not found");
            XposedBridge.log(t);
        }
    }

    /**
     * Colour the hint for the current theme and remember the view.  The same view is reused across
     * opens and SystemUI does not run another colour pass on a uiMode switch, so the shade poller
     * re-applies this on a theme flip (see refreshEmptyShadeInk).
     */
    private static void applyEmptyShadeInk(Object panel) {
        try {
            Object text = XposedHelpers.getObjectField(panel, "mEmptyText");
            if (!(text instanceof android.widget.TextView)) return;
            android.widget.TextView view = (android.widget.TextView) text;
            view.setTextColor(isNight(view.getResources()) ? LIGHT_TINT : DARK_TINT);
            sEmptyShadeInks.put(view, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private static void refreshEmptyShadeInk() {
        for (android.widget.TextView text
                : sEmptyShadeInks.keySet().toArray(new android.widget.TextView[0])) {
            text.setTextColor(sNightUi ? LIGHT_TINT : DARK_TINT);
        }
    }

    /** Every empty hint the module has painted, so a theme flip can repaint it. */
    private static final Map<android.widget.TextView, Boolean> sEmptyShadeInks =
            new java.util.WeakHashMap<>();

    /**
     * Every ongoing-privacy chip the module has seen (weak: they live in the shade, which is rebuilt
     * on a configuration change).
     *
     * <p>The build inflates more than one of them -- the shade's own ({@code ongoing_chip_wrapper} in
     * the expanded status bar), the control centre's and the keyguard's -- and which instance is
     * drawn changes with the page.  Measured: remembering only the last instance the vendor touched
     * left the chip that was actually on screen white, because the passes that ran last belonged to
     * another page's chip.  So all of them are kept and kept in step; see {@link #hookPrivacyChip}.
     */
    private static final java.util.List<java.lang.ref.WeakReference<View>> sPrivacyChips =
            new java.util.ArrayList<>();
    /** The chips currently carrying the module's black, so only those are handed back. */
    private static final Map<View, Boolean> sPrivacyChipInked =
            new java.util.WeakHashMap<>();
    /** Each chip's label view, cached: the ink is re-checked on every draw. */
    private static final Map<View, android.widget.TextView> sPrivacyChipLabels =
            new java.util.WeakHashMap<>();
    /** Guards hook 10's one-shot log line. */
    private static volatile boolean sPrivacyChipLogged = false;


    /**
     * Hook 10 -- the ongoing-privacy chip: the orange camera tile followed by
     * "相机正在使用相机" in the top left of the shade (and the same chip in the control centre).
     *
     * <p><b>Where that text is assembled.</b>  It is neither a layout literal nor
     * {@code privacy_dialog_active_app_usage_1}.  That resource -- {@code "“%1$s”正在使用（%2$s）"} --
     * has exactly one reader in the APK, {@code com.android.systemui.privacy.PrivacyDialogV2#getUsageText},
     * i.e. the dialog that opens when the chip is tapped; searching for it is a dead end.  The chip on
     * screen is the vendor's own class, {@code com.flyme.systemui.privacy.FlymeOngoingPrivacyChip},
     * inflated from {@code res/layout/flyme_ongoing_privacy_chip.xml} into the {@code ViewStub}
     * {@code ongoing_privacy_chip_on_panel_stub}:
     * <pre>
     *   FlymeOngoingPrivacyChip            id/privacy_chip      (0x7f0a0841)
     *     LinearLayout
     *       LinearLayout                 id/icons_container   (0x7f0a0480)   app / type tiles
     *       TextView                     id/text              (0x7f0a0a91)   the label
     * </pre>
     * and its label is assembled at runtime by {@code FlymePrivacyChipBuilder#generateText}:
     * app name + {@code permission_type_using} ("正在") + the privacy type's name ("使用相机") --
     * exactly the "相机正在使用相机" the user sees.  The builder is fed by
     * {@code FlymePrivacyChipController#setPrivacyList} -> {@code #updateView}, which is also the
     * moment the chip appears while the camera is running.
     *
     * <p><b>Why it comes out white on the light cover.</b>  The same root cause as the clock (hook 4),
     * the centre status bar (hook 5), the 收纳箱 box (hook 8) and their close glyph (hook 8b):
     * {@code updateColors(int)} writes {@code Color.WHITE} whenever its argument is 0 <em>or</em>
     * {@code KeyguardUpdateMonitor.usedDefaultColor()} is true, and the latter is true on this build
     * forever because the platform reads the lock-screen wallpaper instead of the module's cover.  The
     * only trigger that would repaint it -- {@code KeyguardUpdateMonitorCallback#onLockWallpaperColorChanged}
     * -- calls {@code access$updateColors(AutoColorUtil.getCurrentColor(true, 2))} <em>only</em> while
     * that flag is false, so nothing ever writes the ink the light plate needs.
     *
     * <p>So the ink is re-asserted after every pass that can set it, the same way
     * {@link #syncHeaderIcons} re-asserts the header icons after {@code updateViewColor}:
     * {@code updateColors} (the vendor's own funnel), {@code onFinishInflate} (inflates white),
     * {@code setPrivacyList} / {@code updateView} (the pass that runs when the camera starts and the
     * label is written) and finally {@code dispatchDraw}, i.e. the drawing chip itself -- which is the
     * only one of those that is reliably the chip the user is looking at, because this build inflates
     * several (shade, control centre, keyguard) and the vendor's last pass usually belongs to another
     * page.  The shade poller ({@link #applyCoverAlpha}) then keeps every known chip in step, which
     * also covers the plate appearing or going away without any vendor pass at all.
     *
     * <p><b>The gate is the white cover itself, not the theme.</b>  The chip is also shown in the
     * lock-screen status bar, where the module deliberately keeps the stock look (the cover is
     * released there, see {@link #applyCoverAlpha}); black ink on the lock wallpaper would be just as
     * unreadable as white ink on the light plate.  So the label is forced <em>only</em> while
     * {@link #whiteCoverUp()} -- the light plate is really on screen -- and handed back to the vendor
     * (by replaying {@code updateColors(0)}, its own "default colour" pass) in every other state:
     * dark mode, shade closed, lock screen.  That is the "有白遮罩时强制黑色，其他情况交还系统" rule.
     */
    private static void hookPrivacyChip(ClassLoader cl) {
        try {
            Class<?> chip = XposedHelpers.findClass(
                    "com.flyme.systemui.privacy.FlymeOngoingPrivacyChip", cl);
            XC_MethodHook fix = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyPrivacyChipInk(param.thisObject);
                }
            };
            XposedBridge.hookAllMethods(chip, "updateColors", fix);
            XposedBridge.hookAllMethods(chip, "onFinishInflate", fix);
            XposedBridge.hookAllMethods(chip, "setPrivacyList", fix);
            XposedBridge.hookAllMethods(chip, "updateView", fix);
            // ... and the drawn instance itself, so the label is right in the very frame the chip is
            // drawn in.  The vendor's own passes are not enough: this build inflates several chips
            // (shade, control centre, keyguard) and the last one to run a pass is usually not the one
            // on screen.
            XposedBridge.hookAllMethods(chip, "dispatchDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    applyPrivacyChipInk(param.thisObject);
                }
            });
            log("10 hook FlymeOngoingPrivacyChip label ink");
        } catch (Throwable t) {
            log("10 FlymeOngoingPrivacyChip not found");
            XposedBridge.log(t);
        }
    }

    /**
     * True while the module's white plate is really on screen: the classic light cover is up (the
     * shade is pulled down, so no keyguard) and the mask has started to fade in.  This -- not the
     * theme -- is what the privacy chip's ink follows; see {@link #hookPrivacyChip}.
     *
     * <p>The two flags come from {@link #applyCoverAlpha}, which runs on the shade poller: it sets
     * {@code sShadeOpen} from the drag and clears both on the lock screen, so a chip drawn there is
     * never touched.
     */
    private static boolean whiteCoverUp() {
        return !sNightUi && sShadeOpen && sMaskAlpha > 0.005f;
    }

    /**
     * Hook 10 body -- classic ink for the privacy chip's label.  Black while the white cover is up;
     * otherwise the vendor's own colour is replayed, so the chip follows the system on the lock
     * screen, in dark mode and with the shade closed.  Every chip the module has ever seen is kept in
     * the list, because the one that is on screen is not necessarily the one the vendor last touched.
     */
    private static void applyPrivacyChipInk(Object chip) {
        if (!(chip instanceof View)) return;
        View v = (View) chip;
        try {
            android.widget.TextView text = privacyChipLabel(v);
            if (text == null) return;
            rememberPrivacyChip(v);
            if (!whiteCoverUp()) {
                // No plate under the chip: nothing to force, and our black has to go back to the
                // vendor's own colour if we were the ones holding it.
                if (sPrivacyChipInked.containsKey(v)) releasePrivacyChip(chip);
                return;
            }
            if (text.getCurrentTextColor() == DARK_TINT) {
                sPrivacyChipInked.put(v, Boolean.TRUE);
                return;
            }
            text.setTextColor(DARK_TINT);
            sPrivacyChipInked.put(v, Boolean.TRUE);
            if (!sPrivacyChipLogged) {
                sPrivacyChipLogged = true;
                log("10 privacy chip label forced 0x" + Integer.toHexString(DARK_TINT));
            }
        } catch (Throwable t) {
            log("10 applyPrivacyChipInk FAILED");
            XposedBridge.log(t);
        }
    }

    /**
     * Put every known chip's ink back in step with the plate.  Called once per shade-poller tick from
     * {@link #applyCoverAlpha}: the chip is normally created (white) while the shade is still closed
     * and its own colour pass never runs again on this build, so the poller is what forces it black
     * when the plate comes up and what hands it back to the system when the plate goes away.
     */
    private static void applyPrivacyChipInks() {
        for (int i = sPrivacyChips.size() - 1; i >= 0; i--) {
            View chip = sPrivacyChips.get(i).get();
            if (chip == null) {
                sPrivacyChips.remove(i);
                continue;
            }
            applyPrivacyChipInk(chip);
        }
    }

    /** Register one chip instance, once.  A handful of instances ever exist. */
    private static void rememberPrivacyChip(View chip) {
        for (int i = 0; i < sPrivacyChips.size(); i++) {
            if (sPrivacyChips.get(i).get() == chip) return;
        }
        sPrivacyChips.add(new java.lang.ref.WeakReference<>(chip));
    }

    /**
     * Hand the chip back to the vendor: replay the only pass that ever writes its ink with the
     * "default colour" argument, which makes it write its own choice again (white while
     * {@code usedDefaultColor()}, the wallpaper-derived colour otherwise).  Used when the white cover
     * goes away -- and safe to re-enter: this call runs hook 10's own body, which finds the cover down
     * and no longer has the chip marked as inked.
     */
    private static void releasePrivacyChip(Object chip) {
        if (chip instanceof View) sPrivacyChipInked.remove((View) chip);
        try {
            XposedHelpers.callMethod(chip, "updateColors", 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The chip's label view: the vendor's own {@code text} field first, the inflated {@code id/text}
     * as the fallback (the id lookup also survives a rebuild that renames the field).  Cached per
     * chip, because the ink is checked on every draw.
     */
    private static android.widget.TextView privacyChipLabel(View chip) {
        android.widget.TextView cached = sPrivacyChipLabels.get(chip);
        if (cached != null) return cached;
        android.widget.TextView found = null;
        try {
            Object t = XposedHelpers.getObjectField(chip, "text");
            if (t instanceof android.widget.TextView) found = (android.widget.TextView) t;
        } catch (Throwable ignored) {
        }
        if (found == null) {
            int id = chip.getResources().getIdentifier("text", "id", SYSUI);
            if (id != 0) {
                View v = chip.findViewById(id);
                if (v instanceof android.widget.TextView) found = (android.widget.TextView) v;
            }
        }
        if (found != null) sPrivacyChipLabels.put(chip, found);
        return found;
    }


    /**
     * Hook 11 -- the x half of the chip's row: in landscape the chip is flush with the notification
     * column's left edge, so the camera tile lines up with the notification cards underneath it.
     *
     * <p><b>Why this needs code rather than a dimen.</b>  The chip's container is
     * {@code ongoing_filter_group}, and its start padding is written by
     * {@code NotificationPanelViewController#updatePadding} as the status bar's content inset
     * ({@code StatusBarContentInsetsProvider.getStatusBarContentInsetsForCurrentRotation().left},
     * zeroed for international products):
     * <pre>
     *   mOnGoingFilterGroup.setPadding(left, notification_ongoing_filter_group_padding_top, ...);
     *   mNotificationContainerParent.setDisplayCutoutPadding(insets);
     * </pre>
     * That inset is the <em>display cutout</em> inset, while the notification column starts at the
     * shade's own content inset -- {@code max(cutout, 48dp)} on this build -- plus the list's 20dp
     * padding.  The two agree only when the cutout is wide enough to win, which is why the measured
     * error is rotation-dependent (see the table in {@link #alignPrivacyChipRow}): a single dp value
     * cannot fix both landscape rotations, let alone portrait, so the module puts the container where
     * the notification column actually is, measured live, in the vendor's own funnel.
     */
    private static void hookPrivacyChipRow(ClassLoader cl) {
        try {
            Class<?> npvc = XposedHelpers.findClass(
                    "com.android.systemui.shade.NotificationPanelViewController", cl);
            XposedBridge.hookAllMethods(npvc, "updatePadding", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    alignPrivacyChipRow(param.thisObject);
                }
            });
            log("11 hook NotificationPanelViewController.updatePadding (privacy chip row)");
        } catch (Throwable t) {
            log("11 NotificationPanelViewController#updatePadding not found");
            XposedBridge.log(t);
        }
    }

    /**
     * The notification column's own start inset is cancelled out on both sides of the comparison --
     * the chip's {@code ongoing_chip_wrapper} carries 20dp of start padding and the notification list
     * insets its cards by the same 20dp -- so "the camera tile's left edge is level with the
     * notification cards" is exactly "the chip container's content box starts where the notification
     * area's content box starts".
     *
     * <p>Measured on the device (3192x1368, both rotations) with the stock code:
     * <pre>
     *                        cutout inset   chip tile   notification cards   error
     *   ROTATION_90 (挖孔左)      130px       204px          238px            -34px  "看着没那么明显"
     *   ROTATION_270 (挖孔右)       0px        74px          238px           -164px  "很明显的向左偏"
     *   portrait                    0px        74px           70px             +4px  (correct already)
     * </pre>
     * so portrait comes out untouched (the column's inset is 0 there too) and each landscape rotation
     * gets exactly the offset it needs.  The value is written absolutely, and only when it actually
     * changes, so the vendor's own {@code setPadding} cannot start a layout loop.
     */
    private static void alignPrivacyChipRow(Object npvc) {
        try {
            Object group = XposedHelpers.getObjectField(npvc, "mOnGoingFilterGroup");
            if (!(group instanceof View)) return;
            View g = (View) group;
            Object panel = XposedHelpers.getObjectField(npvc, "mView");
            if (!(panel instanceof View)) return;
            View panelView = (View) panel;

            // x -- flush with the notification column.
            int left = g.getPaddingStart();
            View column = notificationColumn(panelView);
            int[] gLoc = new int[2];
            g.getLocationInWindow(gLoc);
            if (column != null) {
                int[] cLoc = new int[2];
                column.getLocationInWindow(cLoc);
                left = Math.max(0, cLoc[0] - gLoc[0]);
            }

            // y -- centred on the status bar's own line.  The delta is measured from the two rows
            // themselves and added to whatever padding is in place, so a repeat run measures zero and
            // nothing moves: the correction cannot accumulate and it follows the panel's own layout
            // instead of a hard-coded px value.
            int top = g.getPaddingTop();
            View chip = anyPrivacyChip();
            View status = idCenterStatusBar == 0 ? null : panelView.findViewById(idCenterStatusBar);
            if (chip != null && status != null && status.getHeight() > 0) {
                int[] chipLoc = new int[2];
                int[] stLoc = new int[2];
                chip.getLocationInWindow(chipLoc);
                status.getLocationInWindow(stLoc);
                int delta = (stLoc[1] + status.getHeight() / 2)
                        - (chipLoc[1] + chip.getHeight() / 2);
                if (delta != 0) top = Math.max(0, top + delta);
            }

            if (g.getPaddingStart() == left && g.getPaddingTop() == top) {
                sChipRowLogged = true;
                return;
            }
            g.setPaddingRelative(left, top, g.getPaddingEnd(), g.getPaddingBottom());
            if (!sChipRowLogged) {
                sChipRowLogged = true;
                log("11 privacy chip row: start padding " + g.getPaddingStart() + "px, top "
                        + g.getPaddingTop() + "px");
            }
        } catch (Throwable t) {
            if (!sChipRowLogged) {
                sChipRowLogged = true;
                log("11 alignPrivacyChipRow FAILED");
                XposedBridge.log(t);
            }
        }
    }

    /** One attached chip instance, for the row alignment -- see {@link #sPrivacyChips}. */
    private static View anyPrivacyChip() {
        for (int i = 0; i < sPrivacyChips.size(); i++) {
            View v = sPrivacyChips.get(i).get();
            if (v != null && v.isAttachedToWindow() && v.getHeight() > 0) return v;
        }
        return null;
    }

    /**
     * The notification area's content box: {@code notification_stack_scroller} (whose 20dp start
     * padding the cards sit inside), with the shade header as the fallback -- both are inset by the
     * same amount on this build (0px portrait, 168px landscape).
     */
    private static View notificationColumn(View panel) {
        if (idNotificationScroller == 0) {
            idNotificationScroller = panel.getResources()
                    .getIdentifier("notification_stack_scroller", "id", SYSUI);
            if (idNotificationScroller == 0) log("11 missing id notification_stack_scroller");
        }
        if (idNotificationScroller != 0) {
            View v = panel.findViewById(idNotificationScroller);
            if (v != null) return v;
        }
        return idHeader == 0 ? null : panel.findViewById(idHeader);
    }


    /**
     * The global DarkIconDispatcher re-tints every icon group after the controller has set its
     * colour, so the final word has to be had on TintedIconManager#setTint.  Only the group that
     * lives inside the control-centre status bar is forced dark.
     */
    private static void hookQsIconTint(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.ui.TintedIconManager", cl);
        XposedBridge.hookAllMethods(c, "setTint", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object group = XposedHelpers.getObjectField(param.thisObject, "mGroup");
                    if (!(group instanceof View)) return;
                    if (!insideCenterStatusBar((View) group)) return;
                    // Dark mode: the vendor's automatic colour owns the centre status bar too.
                    if (autoInk()) return;
                    int ink = DARK_TINT;
                    param.args[0] = ink;
                    if (param.args.length > 1) param.args[1] = ink;
                    if (!sTintLogged) {
                        sTintLogged = true;
                        log("5b QS icon tint forced 0x" + Integer.toHexString(ink));
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
        log("5b hook TintedIconManager.setTint");
    }

    private static boolean insideCenterStatusBar(View v) {
        View p = v;
        for (int i = 0; p != null && i < 40; i++) {
            if (p.getId() == idCenterStatusBar) return true;
            android.view.ViewParent parent = p.getParent();
            p = (parent instanceof View) ? (View) parent : null;
        }
        return false;
    }

    /* ------------------- hook 5c: control-centre end margin ------------------ */

    /**
     * Landscape end margin of the control centre's own status bar
     * ({@code QSStatusBar#mSystemIconsContainer}, the battery/percent group).
     *
     * <p>This used to be done by overriding the {@code status_bar_padding_end} dimen for landscape
     * in hook 1, which was the wrong owner: that dimen is the end padding/margin of <em>every</em>
     * status bar in the system -- {@code PhoneStatusBarView#updatePaddings} puts it into
     * {@code status_bar_contents}, {@code KeyguardStatusBarView} and
     * {@code KeyguardBouncerStatusBarView} use it as {@code mStatusBarPaddingEnd}, and the
     * notification ticker reads it too.  The default landscape status bar therefore lost the same
     * 48dp (68dp - 20dp) and its icons ended up far from the right edge, which is exactly the
     * regression this hook removes.
     *
     * <p>Now only the control centre's container is touched, and only in landscape:
     * {@code QSStatusBar.loadDimens()} (process start, density change and
     * {@code updateConfigurationChange()} on rotation) and
     * {@code updateSystemIconsLayoutParams()} (which itself writes
     * {@code mStatusBarPaddingEnd} into that margin) are both followed by the same correction.
     * Portrait keeps the APK's 20dp, as before.
     *
     * <p>The margin alone is not enough, though, because the margin is measured from the row's
     * <em>content box</em>: {@code QSStatusBar.updatePadding()} calls
     * {@code StatusBarContentInsetsProvider#getStatusBarContentInsetsForCurrentRotation()} and puts
     * the result into this view's own padding, so the padding of the current rotation eats into the
     * margin.  Portrait has no side cutout (the punch-hole is top-centre, so left/right insets are
     * 0), and ROTATION_90 puts the punch-hole on the <em>start</em> side, so both of those cases
     * measure 0 -- but ROTATION_270 puts it on the end side and the row gets a 130px
     * {@code paddingEnd} (the cutout inset).  Measured on the device with the shade open:
     * {@code mz_system_icons_container} right edge 2954 at ROTATION_90 and 2824 at ROTATION_270,
     * while the panel cards' right edge is 2953 in both -- i.e. flush at 90 and a 130px gap at 270.
     * The correction therefore subtracts the row's live end padding from the wanted margin, so the
     * icon group always ends on the panel's right boundary no matter which landscape rotation is
     * in effect.  The punch-hole itself is at the middle of the side edge in landscape and this row
     * sits at the top, so nothing is drawn over it.
     */
    private static void hookQsStatusBarEndMargin(ClassLoader cl) {
        try {
            Class<?> qs = XposedHelpers.findClass(
                    "com.flyme.systemui.controlcenter.qs.QSStatusBar", cl);
            XC_MethodHook fix = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) applyQsEndMargin((View) param.thisObject);
                }
            };
            XposedBridge.hookAllMethods(qs, "loadDimens", fix);
            XposedBridge.hookAllMethods(qs, "updateSystemIconsLayoutParams", fix);
            // `updatePadding` is the method that actually moves this row: it writes the *current
            // rotation's* status-bar content insets into the view's own padding, and the icon group
            // is laid out against the padded content box.  Hooking it (and the insets entry point
            // that calls it) means the correction is re-applied whenever the padding changes, not
            // only when the vendor rewrites the margin.
            XposedBridge.hookAllMethods(qs, "updatePadding", fix);
            XposedBridge.hookAllMethods(qs, "updateWindowInsets", fix);
            log("5c hook QSStatusBar landscape end margin");
        } catch (Throwable t) {
            log("5c QSStatusBar not found");
            XposedBridge.log(t);
        }
    }

    private static void applyQsEndMargin(View qsStatusBar) {
        try {
            boolean land = qsStatusBar.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            View icons = qsStatusBar.findViewById(idMzSystemIcons);
            if (!(icons instanceof View)) return;
            android.view.ViewGroup.LayoutParams lp = icons.getLayoutParams();
            if (!(lp instanceof android.view.ViewGroup.MarginLayoutParams)) return;
            android.view.ViewGroup.MarginLayoutParams mlp =
                    (android.view.ViewGroup.MarginLayoutParams) lp;
            // Whatever this row is already inset by has to come off the margin: the icon group is
            // laid out against the padded content box, and the thing it must line up with -- the
            // panel cards' right edge -- does not move with rotation.
            int padEnd = qsStatusBar.getPaddingEnd();
            int want = land
                    ? Math.max(0, Math.round(LS_STATUS_BAR_END_MARGIN_DP
                            * qsStatusBar.getResources().getDisplayMetrics().density) - padEnd)
                    : qsStatusBar.getResources()
                            .getDimensionPixelSize(idStatusBarPaddingEnd);
            if (mlp.getMarginEnd() == want) return;
            mlp.setMarginEnd(want);
            icons.setLayoutParams(mlp);
            log("5c QS status end margin -> " + want + "px (landscape=" + land
                    + ", row paddingEnd=" + padEnd + ")");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** id/dimen/status_bar_padding_end -- read live, so portrait keeps the APK's own value. */
    private static int idStatusBarPaddingEnd = 0x7f071146;

    /* ------------------- shade blur (the visible backdrop behind the cover) ---------------- */

    /**
     * Second of the two blur layers, and the one the window owns: {@code NotificationShadeDepthController}
     * feeds it from {@code BlurUtils.blurRadiusOfRatio(ratio) = lerp(minBlurRadius, maxBlurRadius, ratio)}
     * -- 117..382px on this device, i.e. it grows all the way with the drag.
     *
     * <p>Measured on the device (work/sf_watch.ps1 dumps SurfaceFlinger mid-drag), the layer that really
     * covers the backdrop is the cover's own {@code BackgroundBlurDrawable}, which the window reports as
     * a full-window blur region at the cover's radius; this window radius rides along on the same ramp
     * and is capped at {@link #CLASSIC_SHADE_BLUR_MAX_RATIO} of {@code max_window_blur_radius}
     * (0.05 x 382 = 19px, the "classic" strength), while the cover's region is the
     * {@link #COVER_BLUR_RATIO} (76px) one.  {@link #driveShadeWindowBlur()} pushes the value from the
     * cover's update pass so it does not depend on the platform noticing a change.
     */
    private static final float CLASSIC_SHADE_BLUR_MAX_RATIO = 0.05f;

    private static void hookShadeWindowBlur(ClassLoader cl) {
        try {
            // 1) the raw value the platform hands to SurfaceFlinger is a PACKED int -- the logged
            //    `raw=382` is really `radius >> 8` == 1px -- so the radius has to be scaled at its
            //    source, not at that call.
            Class<?> bu = XposedHelpers.findClass("com.android.systemui.statusbar.BlurUtils", cl);
            XposedBridge.hookAllMethods(bu, "blurRadiusOfRatio", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (CLASSIC_SHADE_BLUR_MAX_RATIO >= 1f) return;
                        Object r = param.getResult();
                        if (!(r instanceof Number)) return;
                        float radius = ((Number) r).floatValue();
                        if (radius <= 0f) return;
                        param.setResult(Float.valueOf(radius * CLASSIC_SHADE_BLUR_MAX_RATIO));
                    } catch (Throwable ignored) {
                    }
                }
            });
            // 2) the shade window controller is needed to push the value per frame.
            Class<?> ctrl = XposedHelpers.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowControllerImpl", cl);
            XposedBridge.hookAllMethods(ctrl, "setBackgroundBlurRadius", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (CLASSIC_SHADE_BLUR_MAX_RATIO >= 1f) return;
                        if (!(param.args[0] instanceof Integer)) return;
                        int blur = ((Integer) param.args[0]).intValue();
                        int want = wantShadeBlurPx();
                        if (want < 0 || want == blur) return;
                        param.args[0] = Integer.valueOf(want);
                    } catch (Throwable ignored) {
                    }
                }
            });
            // 3) the white mask's own blur: LockscreenWallpaperManager gives
            //    wallpaper_foreground_shade (the view this module reuses as the mask) a full-strength
            //    RenderEffect, created once at startup, so it has to be dropped on the instance in use.
            hookRenderEffectBlur(cl);
            // 4) the vendor's classic backdrop: a PRE-BLURRED wallpaper bitmap handed to
            //    classic_notification_shade, whose alpha the depth controller drives from the radius.
            //    There is no radius to scale there, so the layer is suppressed instead.
            hookClassicShadeBackdrop(cl);
            log("9 hook shade blur (cap x" + CLASSIC_SHADE_BLUR_MAX_RATIO + ")");
        } catch (Throwable t) {
            log("9 shade blur hook failed: " + t);
        }
    }

    /**
     * Blur radius (px) the classic shade wants: the raw drag run through the module's blur ramp and
     * capped at {@link #CLASSIC_SHADE_BLUR_MAX_RATIO} of {@code max_window_blur_radius}.  -1 = leave
     * the platform's own value alone (cover not installed yet).
     *
     * <p>The ramp comes from the RAW drag ({@link #sBlurDrive}), never from the white mask's alpha:
     * the mask deliberately stays at 0 below 8% of a pull (255px), and feeding the blur that same
     * alpha would hold the whole backdrop sharp until then and then make it jump.
     */
    private static int wantShadeBlurPx() {
        if (sMaskMaxPx <= 0) return -1;
        int want = Math.round(sMaskMaxPx * CLASSIC_SHADE_BLUR_MAX_RATIO * coverBlurRamp());
        if (want < 0) want = 0;
        if (want > sMaskMaxPx) want = sMaskMaxPx;
        return want;
    }

    /** The module's blur ramp: 0 at the start of the pull, 1 at {@link #COVER_BLUR_FULL_AT}. */
    private static float coverBlurRamp() {
        float d = sBlurDrive;
        if (d <= 0f) return 0f;
        float r = d / COVER_BLUR_FULL_AT;
        return r > 1f ? 1f : r;
    }

    /**
     * Push the wanted blur onto the shade window from the cover's update pass: the setter is a no-op
     * when the value it holds is unchanged, so a value based on the drag would otherwise never reach
     * the window.
     */
    private static void driveShadeWindowBlur() {
        Object ctrl = sShadeWindowCtrl;
        if (ctrl == null) {
            ClassLoader cl = sClassLoader;
            if (cl == null) return;
            ctrl = shadeWindowController(cl);
            if (ctrl == null) return;
        }
        int want = wantShadeBlurPx();
        if (want < 0) return;
        try {
            XposedHelpers.callMethod(ctrl, "setBackgroundBlurRadius", Integer.valueOf(want));
        } catch (Throwable ignored) {
        }
    }

    /** {@code MzBlurUtils.INSTANCE} and its radius setter, resolved once. */
    private static volatile Object sMzBlur;
    private static volatile Method sMzBlurRadius;
    /** The same instance's alpha setter ({@code setBackgroundBlurDrawableAlpha}). */
    private static volatile Method sMzBlurAlpha;
    /** The radius last handed to the cover's blur drawable; -1 = never set. */
    private static volatile int sCoverBlurPx = -1;
    /** The alpha last handed to the cover's blur drawable; -1 = never set. */
    private static volatile int sCoverBlurAlphaPx = -1;

    /**
     * Ramp the cover's own {@code BackgroundBlurDrawable} with the drag.
     *
     * <p>This is the layer the user actually sees: SurfaceFlinger reports it as a full-window blur
     * region with the cover's radius ({@code r76} for 0.2 x 382).  The region exists from the first
     * frames of a pull (with the ramped radius), but it is only <em>composited</em> once the cover is
     * not completely transparent -- see {@link #COVER_MIN_ALPHA} -- so the radius has to be ramped
     * per frame instead of being set to its full value the moment the cover appears.  Only the
     * integer radius really changing is propagated, which is at most ~76 calls per pull.
     */
    private static void driveCoverBlur(View cover) {
        if (cover == null || sBlurMaxPx <= 0) return;
        int want = Math.round(sBlurMaxPx * COVER_BLUR_RATIO * coverBlurRamp());
        if (want == sCoverBlurPx) return;
        try {
            Method m = sMzBlurRadius;
            Object inst = sMzBlur;
            if (m == null || inst == null) {
                Class<?> mz = XposedHelpers.findClass("com.flyme.systemui.utils.MzBlurUtils",
                        cover.getContext().getClassLoader());
                inst = XposedHelpers.getStaticObjectField(mz, "INSTANCE");
                m = XposedHelpers.findMethodExact(mz, "setBackgroundBlurDrawableBlurRadius",
                        View.class, int.class, boolean.class);
                sMzBlur = inst;
                sMzBlurRadius = m;
            }
            m.invoke(inst, cover, Integer.valueOf(want), Boolean.FALSE);
            sCoverBlurPx = want;
        } catch (Throwable ignored) {
        }
    }

    /**
     * Set the alpha of the cover's blur drawable ({@code MzBlurUtils.setBackgroundBlurDrawableAlpha}).
     *
     * <p>Used to take the blur region out of the composition on the brightness mirror page -- see
     * {@link #MIRROR_BLUR_ALPHA} -- and to put it back (255) the moment the mirror goes away.  The
     * value is cached so the per-frame gate does not call into the drawable when nothing changed.
     */
    private static void driveCoverBlurAlpha(View cover, int want) {
        if (cover == null || want == sCoverBlurAlphaPx) return;
        try {
            Method m = sMzBlurAlpha;
            Object inst = sMzBlur;
            if (m == null || inst == null) {
                Class<?> mz = XposedHelpers.findClass("com.flyme.systemui.utils.MzBlurUtils",
                        cover.getContext().getClassLoader());
                inst = XposedHelpers.getStaticObjectField(mz, "INSTANCE");
                m = XposedHelpers.findMethodExact(mz, "setBackgroundBlurDrawableAlpha",
                        View.class, int.class, boolean.class);
                sMzBlur = inst;
                sMzBlurAlpha = m;
            }
            m.invoke(inst, cover, Integer.valueOf(want), Boolean.FALSE);
            sCoverBlurAlphaPx = want;
        } catch (Throwable ignored) {
        }
    }

    /**
     * Is the brightness mirror page ("亮度调整页") up right now?
     *
     * <p>Two signals, because either one alone can miss: the vendor's own
     * {@code showMirror()}/{@code hideMirror()} (hooked in {@link #hookBrightnessMirrorBlur}) and the
     * mirror container's visibility -- {@code res/layout/brightness_mirror_container.xml} ships it as
     * {@code android:visibility="invisible"}, and only {@code showMirror()} sets it to
     * {@code VISIBLE}, so "visible" can only mean the page is up.
     *
     * <p>The view is the authoritative one once it can be found, and it writes the flag back: the
     * vendor re-inflates the container ({@code reinflate()} on mode/rotation changes) and a missed
     * {@code hideMirror()} would otherwise leave the blur suppressed for the next pull-down.  The
     * cached view is re-resolved whenever it is detached, for the same reason.  This runs on every
     * gate tick (16ms while the shade is moving), hence the caching.
     */
    private static boolean brightnessMirrorUp() {
        View page = mirrorPageView();
        if (page != null) {
            boolean up = page.getVisibility() == View.VISIBLE;
            sBrightnessMirrorUp = up;
            return up;
        }
        return sBrightnessMirrorUp;
    }

    /** The mirror container, cached while it stays attached to the shade window. */
    private static View mirrorPageView() {
        View page = sMirrorPage;
        if (page != null && page.isAttachedToWindow()) return page;
        View cover = sCover;
        if (cover == null) return null;
        View root = cover.getRootView();
        if (root == null) return null;
        page = root.findViewById(idBrightnessMirror);
        sMirrorPage = page;
        return page;
    }

    /** {@code max_window_blur_radius}, read from the cover's resources when the cover is installed. */
    private static volatile int sMaskMaxPx = 382;

    /**
     * Scale the blur the white mask carries itself.
     *
     * <p>{@code LockscreenWallpaperManager} sets a full-strength
     * {@code RenderEffect.createFlymeBlurEffect(maxBlurRadius)} as the RenderEffect of
     * {@code wallpaper_foreground_shade} -- the view this module reuses as the white mask -- so the
     * veil brings a blurred copy of the backdrop with it.  Scaling happens at the single factory the
     * effect is built from.
     */
    private static void hookRenderEffectBlur(ClassLoader cl) {
        try {
            Class<?> re = XposedHelpers.findClass("android.graphics.RenderEffect", cl);
            for (java.lang.reflect.Method m : re.getDeclaredMethods()) {
                if (!m.getName().contains("BlurEffect")) continue;
                if (m.getParameterTypes().length != 1) continue;
                if (m.getParameterTypes()[0] != float.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (CLASSIC_SHADE_BLUR_MAX_RATIO >= 1f) return;
                            float radius = ((Float) param.args[0]).floatValue();
                            if (radius <= 0f) return;
                            param.args[0] = Float.valueOf(radius * CLASSIC_SHADE_BLUR_MAX_RATIO);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable t) {
            log("9 RenderEffect hook failed: " + t);
        }
    }

    /**
     * Suppress the vendor's classic-shade backdrop.
     *
     * <p>{@code NotificationShadeDepthController} gives {@code classic_notification_shade} a background
     * of {@code LockWallpaperCollection.getBlurLockWallpaper()} -- a PRE-BLURRED bitmap -- and reveals
     * it according to {@code MathUtils.constrainedMap(0, 1, minBlurRadius, maxBlurRadius, radius)}.
     * Because the blur is baked into the bitmap, any fraction of it that is revealed is at full
     * strength and there is no radius to scale, so the layer is hidden instead while the module's own
     * cover is on screen.
     */
    private static void hookClassicShadeBackdrop(ClassLoader cl) {
        try {
            Class<?> depth = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.NotificationShadeDepthController", cl);
            // updateScrimNotificationShadeBg() is the method that hands the bitmap over and sets the
            // alpha; computeBlurAndZoomOut() only computes the radius.
            XposedBridge.hookAllMethods(depth, "updateScrimNotificationShadeBg", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (keyguardShowing()) return;   // lock screen keeps the stock backdrop
                        View v = (View) XposedHelpers.getObjectField(
                                param.thisObject, "classicNotificationShade");
                        if (v == null) return;
                        if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                        if (v.getBackground() != null) v.setBackground(null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("9 classic shade backdrop hook failed: " + t);
        }
    }

    /* ---------------------------- cover ------------------------------- */

    private static void installCover(ViewGroup root) {
        if (sCoverInstalled && sCover != null && sCover.getParent() != null) return;
        View bg = root.findViewById(idCover);
        if (!(bg instanceof ViewGroup)) {
            log("cover: keyguard_background_frame not found (0x" + Integer.toHexString(idCover) + ")");
            return;
        }
        ViewGroup frame = (ViewGroup) bg;
        blurWallpaperMirror(frame);
        FrameLayout cover = new FrameLayout(frame.getContext());
        boolean blurred = applyBackgroundBlur(cover);
        View scrim = new View(frame.getContext());
        // The white mask hides the blur (it was 89% opaque), so it is transparent for now: the
        // blurred wallpaper itself shows, which is what mode 0 looks like.  Set
        // The mask lives on OUR scrim, not on the vendor's wallpaper layer: that layer is owned by
        // the platform, which resets it as soon as the shade settles, so the white used to vanish on
        // release (visible while dragging, gone after letting go).  A view we own cannot be reset.
        final ColorDrawable scrimDrawable =
                new ColorDrawable(MASK_ON_MIRROR ? 0x00000000 : coverScrimColor());
        scrim.setBackground(scrimDrawable);
        cover.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sCoverScrim = scrimDrawable;
        sCoverScrimNight = sNightUi;
        if (!blurred) log("cover: no blur behind, mask only");
        cover.setAlpha(0f);
        cover.setVisibility(View.INVISIBLE);
        cover.setClickable(false);
        cover.setFocusable(false);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sCover = cover;
        sCoverInstalled = true;
        log("cover installed");

        // Poll instead of relying on callbacks: the shade can be opened by gestures, by the
        // notification panel, or by the control centre, and the cover has to follow all of them.
        // The interval is short because the wallpaper layers have to disappear within the first
        // frames of the pull-down, otherwise the wallpaper is visible during the whole animation.
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    applyCoverAlpha();
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                // 60fps while the shade is moving (smoothness), idle otherwise (battery).  The
                // lock screen's own pull-down runs on this tick too; its panel is settled most of
                // the time while it is open, so only the frames in which it really moves go to 60fps.
                boolean fast = sShadeOpen
                        || (sLockPullOn
                        && android.os.SystemClock.uptimeMillis() - sLockLastMove < 120L)
                        || android.os.SystemClock.uptimeMillis() < sLockRestoreUntil;
                handler.postDelayed(this, fast ? 16 : 150);
            }
        }, 40);
    }

    /**
     * Flyme's own "blur behind the window" drawable; this is what makes the content underneath the
     * shade visible but blurred, like the old white blur mask.
     */
    /**
     * Mode 0 (separate notification / control centre) gets its blurred backdrop from
     * {@code LockscreenWallpaperManager$setNotificationShadeWindowView$7$1}, which calls
     * {@code MzBlurUtils.setBackgroundBlurDrawableZAdjustment(lockWallpaperMirrorView, -1, false)}.
     * That whole flow is inside the {@code isClassicsMode()} branch, so classic mode never runs it
     * and the shade shows the raw wallpaper.  Reproduce the vendor's call on the vendor's own view.
     */
    private static void blurWallpaperMirror(ViewGroup root) {
        if (sMirrorBlurred) return;
        try {
            View mirror = root.findViewById(idWallpaperMirror);
            if (mirror == null) {
                log("mirror blur: lock_wallpaper_mirror not found");
                return;
            }
            if (mirror.getVisibility() != View.VISIBLE) return;
            Class<?> mz = XposedHelpers.findClass("com.flyme.systemui.utils.MzBlurUtils",
                    root.getContext().getClassLoader());
            Object inst = XposedHelpers.getStaticObjectField(mz, "INSTANCE");
            XposedHelpers.callMethod(inst, "setBackgroundBlurDrawableZAdjustment",
                    mirror, -1, false);
            sMirrorBlurred = true;
            log("mirror blur applied (mode-0 path)");
        } catch (Throwable t) {
            log("mirror blur failed: " + t);
        }
    }

    private static boolean applyBackgroundBlur(View cover) {
        try {
            Class<?> mz = XposedHelpers.findClass("com.flyme.systemui.utils.MzBlurUtils",
                    cover.getContext().getClassLoader());
            Object inst = XposedHelpers.getStaticObjectField(mz, "INSTANCE");
            Object support = XposedHelpers.callMethod(inst, "isSupportControlBlur");
            log("cover: isSupportControlBlur=" + support);
            if (Boolean.FALSE.equals(support)) return false;
            // View-level BackgroundBlurDrawable on the cover: unlike setting the shade window's
            // radius from outside (which the platform overwrites every frame), this one actually
            // drives what is on screen.
            int radius = 80;
            try {
                int id = cover.getResources().getIdentifier(
                        "max_window_blur_radius", "dimen", SYSUI);
                if (id != 0) {
                    sBlurMaxPx = cover.getResources().getDimensionPixelSize(id);
                    radius = Math.round(sBlurMaxPx * COVER_BLUR_RATIO);
                }
            } catch (Throwable ignored) {
            }
            Method m = XposedHelpers.findMethodExact(mz, "setBackgroundBlurDrawable",
                    View.class, int.class);
            m.invoke(inst, cover, radius);
            log("cover: background blur drawable set radius=" + radius);
            return true;
        } catch (Throwable t) {
            log("cover: background blur failed: " + t);
            return false;
        }
    }

    /**
     * Reuse Flyme's own wallpaper-blur drawable (the one the notification cards use) so the cover
     * shows a blurred wallpaper tinted white -- the old "white blur mask".
     */
    private static boolean applyBlurBackground(View cover) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(
                    "com.flyme.systemui.wallpaper.WallpaperBlurDrawableManager",
                    cover.getContext().getClassLoader());
            Object mgr = XposedHelpers.callStaticMethod(mgrClass, "getInstance", cover.getContext());
            if (mgr == null) return false;
            Method m = XposedHelpers.findMethodExact(mgrClass, "addBlurDrawableTo",
                    View.class, int.class, float.class);
            m.invoke(mgr, cover, COVER_COLOR, COVER_BLUR_RADIUS);
            log("cover: wallpaper blur drawable applied");
            return true;
        } catch (Throwable t) {
            log("cover: blur drawable failed: " + t);
            return false;
        }
    }

    /**
     * The cover may only be opaque while the shade is pulled down; on the lock screen it has to
     * stay invisible.  The authoritative signal is the shade-expansion StateFlow of the shade
     * interactor held by the wallpaper manager.
     */
    /**
     * In landscape the stock "classical" header collapses everything into one small line
     * (12dp clock, solar+lunar side by side, 17dp title).  The old design used a large clock,
     * the lunar date on a second line and a much bigger "??????" title, so put those back.
     * Portrait is explicitly restored to the stock values because nothing else resets them.
     */
    private static void fixHeader(View header) {
        boolean landscape = header.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        float d = header.getResources().getDisplayMetrics().density;
        Resources res = header.getResources();

        Object dateGroup = XposedHelpers.getObjectField(header, "mDateGroup");
        Object lunar = XposedHelpers.getObjectField(header, "mLunarView");
        Object time = XposedHelpers.getObjectField(header, "mTime");
        Object date = XposedHelpers.getObjectField(header, "mDateExpanded");
        Object label = XposedHelpers.getObjectField(header, "mControlCenterLabel");

        if (dateGroup instanceof android.widget.LinearLayout) {
            ((android.widget.LinearLayout) dateGroup)
                    .setOrientation(android.widget.LinearLayout.VERTICAL);
            android.view.ViewGroup.LayoutParams dlp =
                    ((android.widget.LinearLayout) dateGroup).getLayoutParams();
            if (dlp instanceof android.widget.LinearLayout.LayoutParams) {
                // CENTER_VERTICAL gravity splits a margin, so the top margin has to be twice the
                // wanted shift; without it the date block floats ~8dp above the clock's centre.
                ((android.widget.LinearLayout.LayoutParams) dlp).topMargin =
                        Math.round((landscape ? LS_DATE_TOP_MARGIN_DP : 0f) * d);
                ((android.widget.LinearLayout) dateGroup).setLayoutParams(dlp);
            }
        }
        // The lunar's top margin and the clock's size come from flyme_lunar_date_view_top_margin_*
        // and classical_flyme_clock_text_size, both overridden in hook 1 -- no code needed here.

        float dateDp = landscape ? LS_DATE_DP : dimenDp(res, "flyme_date_group_text_size", 12f);
        float titleDp = landscape ? LS_TITLE_DP : 17f;

        setTextSizePx(date, dateDp * d);
        setTextSizePx(lunar, dateDp * d);
        setTextSizePx(label, titleDp * d);

        // the stock label is a fixed 24dp tall view, which clips a bigger title
        if (label instanceof android.widget.TextView) {
            android.view.ViewGroup.LayoutParams lp =
                    ((android.widget.TextView) label).getLayoutParams();
            if (lp != null && lp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                ((android.widget.TextView) label).setLayoutParams(lp);
            }
        }

        // The row shift is applied from onLayout (see shiftHeaderRow): updateDateLocation is the
        // landscape path and is not called in portrait at all.
    }

    /**
     * Shift the whole header row (clock + date + collapse button) down.  All three live in the time
     * container, so they cannot drift apart.  Translation only, and value-guarded; the one thing that
     * can still request a layout pass is the header's minimum height below, and only until it has
     * been applied once.
     *
     * <p>Portrait carries the page shift ({@link #BELOW_CLOCK_SHIFT_DP}) as well, so the clock keeps
     * its distance to the notification list once that list is translated by the same amount.
     */
    private static void shiftHeaderRow(View header) {
        try {
            boolean landscape = header.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            Object tc = XposedHelpers.getObjectField(header, "mTimeContainer");
            if (!(tc instanceof View) && header instanceof View) {
                tc = ((View) header).findViewById(idTimeContainer);
            }
            if (!(tc instanceof View)) return;
            View v = (View) tc;
            float d = header.getResources().getDisplayMetrics().density;
            float want = (landscape
                    ? LS_HEADER_ROW_SHIFT_LAND_DP
                    : LS_HEADER_ROW_SHIFT_DP + BELOW_CLOCK_SHIFT_DP) * d;
            if (v.getTranslationY() != want) v.setTranslationY(want);
            // The row has to stay inside the header's own bounds: the header composites its content
            // through a layer sized to those bounds, so clearing clipChildren/clipToPadding on it
            // changes nothing (measured in portrait with the page shift).  Portrait therefore grows
            // the header through status_bar_headerview_height.  Landscape cannot -- MzQSImpl
            // .updateResource() zeroes the header's minimum height in classic landscape, so the
            // header is exactly as tall as the row.  The same offset is given back as bottom padding
            // instead: the row is top-aligned, so only the header's lower edge moves.
            if (landscape && header instanceof ViewGroup) {
                ViewGroup hg = (ViewGroup) header;
                int padBottom = Math.round(want);
                if (hg.getPaddingBottom() != padBottom) {
                    hg.setPadding(hg.getPaddingLeft(), hg.getPaddingTop(),
                            hg.getPaddingRight(), padBottom);
                }
            } else if (header instanceof ViewGroup) {
                // The landscape branch above hijacks the header's bottom padding, and nothing on
                // this build ever resets it: StatusBarHeaderView#updateResources only rewrites the
                // LEFT/RIGHT padding (it passes getPaddingTop()/getPaddingBottom() straight back),
                // so the 4dp survives the trip back to portrait.  Measured: fresh portrait
                // pad=70,280,70,0; after one landscape round-trip pad=70,280,70,14.  Drop it here.
                ViewGroup hg = (ViewGroup) header;
                if (hg.getPaddingBottom() != 0) {
                    hg.setPadding(hg.getPaddingLeft(), hg.getPaddingTop(), hg.getPaddingRight(), 0);
                }
                // ... and keep the shifted row inside the header's own bounds.  The classic
                // container's header already gets its minimum height from
                // status_bar_headerview_height (MzQSImpl.updateResource, hook 1), but the LOCK
                // SCREEN's control centre is a different view: ControlCenterPanelView
                // (res/layout/control_center.xml) inflates its own mz_header from
                // mz_status_bar_header.xml, whose minHeight is the layout attribute -- resolved
                // natively, so the +32dp dimen override never reaches it and that header stays at
                // the stock 483px.  The row is still translated there, so without this the clock was
                // clipped by the header's layer AND the panel sat 112px too high:
                // setDistanceBetweenHeaderAndQscontainer() puts the QS container at (this header's
                // height + distance_between_header_and_qscontainer), i.e. 483+42 = 525 instead of
                // 595+42 = 637.  Applying the same minimum height the classic header gets fixes
                // both at once, so both control centres put their cards on the same line.
                int wantMin = header.getResources().getDimensionPixelSize(idHeaderHeight);
                if (wantMin > 0 && hg.getMinimumHeight() < wantMin) {
                    hg.setMinimumHeight(wantMin);
                    // ControlCenterPanelView reads the header's height in its own onLayout, which
                    // has already run by the time this pass reaches the header: ask for one more
                    // traversal so the QS container's padding follows in the same frame.
                    if (hg.getParent() instanceof View) ((View) hg.getParent()).requestLayout();
                }
            }
            if (header instanceof ViewGroup) {
                ViewGroup hg = (ViewGroup) header;
                if (hg.getClipChildren()) hg.setClipChildren(false);
                if (hg.getClipToPadding()) hg.setClipToPadding(false);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Portrait: drop the tile-edit page's first row onto the clock's line -- see
     * {@link #EDIT_HEADER_SHIFT_DP}.
     *
     * <p>Called from the same {@code RelativeLayout#onLayout} hook as {@link #shiftHeaderRow}, because
     * the header has no onLayout of its own to hook and the edit row is only laid out while editing is
     * on.  The margin is applied to {@code mEditContainer} (falling back to the view id), and only
     * while {@code StatusBarHeaderView#isShowingCustomize()} is true -- otherwise the row would sit
     * pushed down in its hidden state and be a frame late on the way in.  A top margin (not a
     * translation) is used so the row is inside the header's composited bounds, where the same
     * 161px is still comfortably within the header's 595px.
     *
     * <p>The tile grid is untouched: it lives in {@code mz_quick_settings_container} (top 637px), a
     * different child of the control centre, so only the edit row moves.
     */
    private static void shiftEditHeaderRow(View header) {
        try {
            Object showing = XposedHelpers.callMethod(header, "isShowingCustomize");
            if (!(showing instanceof Boolean) || !((Boolean) showing)) return;
            Object ec = XposedHelpers.getObjectField(header, "mEditContainer");
            if (!(ec instanceof View)) ec = header.findViewById(idEditContainer);
            if (!(ec instanceof View)) return;
            View v = (View) ec;
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (!(lp instanceof android.view.ViewGroup.MarginLayoutParams)) return;
            int want = dpToWrittenPx(header.getResources(), EDIT_HEADER_SHIFT_DP);
            android.view.ViewGroup.MarginLayoutParams mlp =
                    (android.view.ViewGroup.MarginLayoutParams) lp;
            if (mlp.topMargin != want) {
                mlp.topMargin = want;
                v.setLayoutParams(mlp);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Keeps the floating brightness bar on top of the control centre's brightness slider -- see
     * {@link #BRIGHTNESS_MIRROR_OFFSET_DP} for the measurement.
     *
     * <p>Hooked on the vendor's own placement call ({@code MirrorController#setLocationAndSize}) and
     * applied right after it, because the stock controller finishes by writing its own
     * {@code translationX}; correcting from a draw callback would be undone on the next placement.
     * The offset is added to whatever the vendor just wrote, so its placement (and the vertical
     * alignment, which is already correct) is preserved.
     */
    private static void hookBrightnessMirrorAlign(final ClassLoader cl) {
        XC_MethodHook fix = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object mirror = XposedHelpers.getObjectField(param.thisObject, "mBrightnessMirror");
                    if (!(mirror instanceof ViewGroup)) return;
                    ViewGroup bar = (ViewGroup) mirror;
                    // 1. size: the bar's card is the pressed slider cloned in, so it carries the
                    //    press animation's 0.95 scale; undo it -> same 188x562 as the slider.
                    for (int i = 0; i < bar.getChildCount(); i++) {
                        View card = bar.getChildAt(i);
                        if (card == null) continue;
                        if (card.getScaleX() != 1f) card.setScaleX(1f);
                        if (card.getScaleY() != 1f) card.setScaleY(1f);
                        // ... and the same sun-icon size the control centre uses: the module's own
                        //     constant (the vendor animates the icon's frame, so the view size is
                        //     what has to match).
                        setSize(card.findViewById(idSliderIcon),
                                Math.round(LS_BRIGHTNESS_ICON_DP
                                        * bar.getResources().getDisplayMetrics().density));
                        // ... and the same corner radius as the slider (see corners below).
                        applyMirrorCorner(bar, card);
                    }
                    // 2. position: x on the container (vendor writes translationX), y on the container
                    //    too -- the card must stay inside it, or the container's clip cuts the bar.
                    float addX = dpToWrittenPx(bar.getResources(), BRIGHTNESS_MIRROR_OFFSET_DP);
                    bar.setTranslationX(bar.getTranslationX() + addX);
                    float addY = dpToWrittenPx(bar.getResources(), BRIGHTNESS_MIRROR_OFFSET_Y_DP);
                    bar.setTranslationY(bar.getTranslationY() + addY);
                    if (!sMirrorLogged) {
                        sMirrorLogged = true;
                        StringBuilder sb = new StringBuilder("9 brightness mirror: children=");
                        sb.append(bar.getChildCount()).append(" bar=").append(bar.getWidth())
                          .append("x").append(bar.getHeight())
                          .append(" pad=").append(bar.getPaddingLeft()).append(',').append(bar.getPaddingTop())
                          .append(" x+=").append(addX).append(" y+=").append(addY);
                        for (int i = 0; i < bar.getChildCount(); i++) {
                            View c = bar.getChildAt(i);
                            sb.append(" | child").append(i).append(' ')
                              .append(c.getClass().getSimpleName()).append(' ')
                              .append(c.getWidth()).append('x').append(c.getHeight())
                              .append(" scale=").append(c.getScaleX()).append(',').append(c.getScaleY());
                        }
                        log(sb.toString());
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        int n = 0;
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.policy.BrightnessMirrorController", cl);
            XposedBridge.hookAllMethods(c, "setLocationAndSize", fix);
            n++;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> c2 = XposedHelpers.findClass(
                    "com.android.systemui.settings.brightness.ui.viewModel.BrightnessMirrorViewModel", cl);
            // this variant keeps neither the mirror nor its id field: read the view from the
            // LocationAndSize flow target instead -- not reachable, so it is left alone.
            log("9 BrightnessMirrorViewModel present (legacy controller still wired on this build)");
            XposedBridge.hookAllMethods(c2, "setLocationAndSize", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object slider = XposedHelpers.getObjectField(param.thisObject, "_toggleSlider");
                        if (slider == null) return;
                        Object rootView = XposedHelpers.callMethod(slider, "getRootView");
                        if (!(rootView instanceof View)) return;
                        ViewGroup mirror = (ViewGroup) ((View) rootView).getParent();
                        if (mirror == null) return;
                        float add = dpToWrittenPx(mirror.getResources(), BRIGHTNESS_MIRROR_OFFSET_DP);
                        mirror.setTranslationX(mirror.getTranslationX() + add);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        log("9 hook BrightnessMirror setLocationAndSize (" + n + " controller)");
    }

    /**
     * Drop the backdrop blur for as long as the brightness mirror page is up, and put it back on
     * release -- the two edges the user described ("长按亮度条…不松手" until "松手回到控制中心主页").
     *
     * <p>The page's own code is {@code BrightnessMirrorController#showMirror()/#hideMirror()}, which
     * is also what fades the panel out and back in, so the flag they set and the panel's own state can
     * never disagree.  {@link #applyCoverAlpha()} is called straight away from both hooks so the blur
     * is gone in the same frame the bar appears (waiting for the next gate tick would leave one blurred
     * frame), and the gate keeps forcing {@link #MIRROR_BLUR_PX} on every tick while the flag is up,
     * because the ramp would otherwise be re-driven from the panel's expansion (measured: panel=1.0 on
     * that page, which is a full 76px radius).
     *
     * <p>{@link #brightnessMirrorUp()} also accepts the mirror container's visibility as a signal, so
     * a build that reaches the page through another path is still covered.
     */
    private static void hookBrightnessMirrorBlur(final ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.policy.BrightnessMirrorController", cl);
            XC_MethodHook show = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sBrightnessMirrorUp = true;
                    sMirrorBlurLogged = false;
                    sMirrorWindowBlurLogged = false;
                    log("9 brightness mirror shown: backdrop blur -> " + MIRROR_BLUR_PX + "px");
                    try {
                        applyCoverAlpha();
                    } catch (Throwable ignored) {
                    }
                }
            };
            XC_MethodHook hide = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sBrightnessMirrorUp = false;
                    sMirrorBlurLogged = false;
                    sMirrorWindowBlurLogged = false;
                    log("9 brightness mirror hidden: backdrop blur restored");
                    try {
                        applyCoverAlpha();
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(c, "showMirror", show);
            XposedBridge.hookAllMethods(c, "hideMirror", hide);
            log("9 hook BrightnessMirror blur suppression");
        } catch (Throwable t) {
            log("9 brightness mirror blur hook failed: " + t);
        }
        hookShadeWindowBlurPush(cl);
    }

    /**
     * Zero the SHADE WINDOW's own blur while the mirror page is up.
     *
     * <p>This is the layer that was left over after the cover's region was dropped (measured: the
     * desktop's icon edges still ramped over ~30px instead of one step, and
     * {@code dumpsys SurfaceFlinger} showed the shade layer at
     * {@code backgroundBlurRadius=262162} == radius 18px).  It is a collision between the module's cap
     * and the vendor's own mirror handling:
     *
     * <pre>
     *   NotificationShadeDepthController#computeBlurAndZoomOut():
     *       int ratio = (int) ((supportsBlursOnWindows() ? i : 0)
     *                          * (1.0f - this.brightnessMirrorSpring.getRatio()));
     * </pre>
     *
     * where {@code i} is the max of the {@code blurRadiusOfRatio(...)} values -- which hook 9 scales to
     * 5% -- and {@code setBrightnessMirrorVisible(true)} animates that spring to
     * {@code blurRadiusOfRatio(1.0f)}.  Uncapped that is 382, so {@code getRatio()} reaches 1 and the
     * shade blur is driven to 0 (the vendor's way of saying "the mirror page has no backdrop blur").
     * Capped at 19 the spring only reaches ratio 0.05, so {@code (1 - ratio)} stays 0.95 and 18px of
     * window blur survive -- exactly the residue that was left.
     *
     * <p>{@code BlurUtils.applyBlur()} is where the radius finally reaches SurfaceFlinger
     * ({@code Transaction#setBackgroundBlurRadius}, BlurUtils.java:87), and it is called with several
     * different windows (keyguard, dream overlay, shutdown UI), so the radius is zeroed only for the
     * shade window's own ViewRootImpl -- the one this module's cover lives in.
     */
    private static void hookShadeWindowBlurPush(final ClassLoader cl) {
        try {
            Class<?> bu = XposedHelpers.findClass("com.android.systemui.statusbar.BlurUtils", cl);
            XposedBridge.hookAllMethods(bu, "applyBlur", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!brightnessMirrorUp()) return;
                        if (param.args.length < 2 || !(param.args[1] instanceof Integer)) return;
                        if (!isShadeViewRoot(param.args[0])) return;
                        if (((Integer) param.args[1]).intValue() == MIRROR_BLUR_PX) return;
                        if (!sMirrorWindowBlurLogged) {
                            sMirrorWindowBlurLogged = true;
                            log("9 mirror page: shade window blur "
                                    + param.args[1] + "px -> " + MIRROR_BLUR_PX + "px");
                        }
                        param.args[1] = Integer.valueOf(MIRROR_BLUR_PX);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("9 hook BlurUtils.applyBlur (shade window blur kill)");
        } catch (Throwable t) {
            log("9 shade blur push hook failed: " + t);
        }
    }

    /** Guards {@link #hookShadeWindowBlurPush}'s one-shot log line. */
    private static volatile boolean sMirrorWindowBlurLogged = false;

    /**
     * Is this ViewRootImpl the shade window's -- i.e. the one the module's cover is drawn into?  Used
     * so the mirror-page blur kill cannot touch the keyguard, dream or shutdown windows that go
     * through the same {@code BlurUtils}.
     */
    private static boolean isShadeViewRoot(Object viewRootImpl) {
        View cover = sCover;
        if (cover == null || viewRootImpl == null) return false;
        View root = cover.getRootView();
        if (root == null) return false;
        try {
            return XposedHelpers.callMethod(root, "getViewRootImpl") == viewRootImpl;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Give the floating bar the floating "card" corner of the control-centre slider.
     *
     * <p>Where the control centre's corner actually comes from, in order (all measured/traced on the
     * device):
     * <ol>
     *   <li>{@link #styleSliderWrapper} paints {@code slider_wrapper} -- the card behind both
     *       sliders -- with a round-rect of radius {@code qs_corner_radius} (18dp =
     *       63px here); the surface is the module's own {@link ClassicCardFill} (see
     *       {@link #applyQsCardFill}), which carries that radius as the fill's corner radii;</li>
     *   <li>{@link #narrowSliderTracks} rounds each slider <em>track</em> with
     *       {@code SystemUICommonUtils.setViewSmoothCorner(track, track*9/49)} -- that is the small
     *       radius of the coloured bar itself, not the card;</li>
     *   <li>what the eye reads as "the card corner" is therefore the wrapper's 63px radius, and the
     *       card the floating bar brings along is a different view (the pressed
     *       {@code BrightnessSliderView} cloned into the mirror) that carries the stock corner.</li>
     * </ol>
     *
     * <p>So the bar is given the same shape explicitly: a {@code ViewOutlineProvider} round-rect at
     * the fitted radius plus {@code clipToOutline} -- the combination that visibly changed the corner
     * on the device.  The card's own background is deliberately left alone: it carries the blur
     * drawable, and an opaque fill here is what made the bar read as a flat white plate on the home
     * screen (measured before/after).  The radius is never allowed past a pill ({@code w/2}) and is
     * re-applied on pre-draw, because at placement time the card has no measured width yet.
     */
    private static void applyMirrorCorner(final ViewGroup bar, final View card) {
        try {
            if (!sMirrorCornerDone.add(card)) return;
            card.setClipToOutline(true);
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline outline) {
                    float r = mirrorCornerRadius(bar, v);
                    if (r > 0f) outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            if (card.getViewTreeObserver() != null) {
                card.getViewTreeObserver().addOnPreDrawListener(
                        new android.view.ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                card.invalidateOutline();
                                card.getViewTreeObserver().removeOnPreDrawListener(this);
                                return true;
                            }
                        });
            }
            if (!sMirrorCornerLogged) {
                sMirrorCornerLogged = true;
                log("9 brightness mirror corner: r=" + mirrorCornerRadius(bar, card)
                        + "px outline + clip (like the control-centre card)");
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** The corner radius to draw for the bar's card -- see {@link #applyMirrorCorner}. */
    private static float mirrorCornerRadius(ViewGroup bar, View card) {
        float radius = 0f;
        try {
            radius = bar.getResources().getDimensionPixelSize(idQsCornerRadius);
        } catch (Throwable ignored) {
        }
        if (radius <= 0f) {
            int id = bar.getResources().getIdentifier("qs_corner_radius", "dimen", SYSUI);
            if (id != 0) radius = bar.getResources().getDimensionPixelSize(id);
        }
        radius *= BRIGHTNESS_MIRROR_CORNER_RATIO;
        int w = card.getWidth() > 0 ? card.getWidth() : bar.getWidth();
        return w > 0 ? Math.min(radius, w / 2f) : radius;
    }

    /** Cards already given the bar corner treatment (weak keys; see {@link #applyMirrorCorner}). */
    private static final java.util.Set<View> sMirrorCornerDone =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<View, Boolean>());
    private static volatile boolean sMirrorCornerLogged = false;

    private static void setTextSizePx(Object tv, float px) {
        if (tv instanceof android.widget.TextView) {
            ((android.widget.TextView) tv).setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        }
    }    private static float dimenDp(Resources res, String name, float fallback) {
        try {
            int id = res.getIdentifier(name, "dimen", SYSUI);
            if (id != 0) return res.getDimensionPixelSize(id) / res.getDisplayMetrics().density;
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /* ------------------ shade status bar (control centre) ---------------- */

    /**
     * The sliders stretch over the whole column, so the track reads as one wide block.  The old
     * design kept a track:gap ratio of 49:18.7 on a 153.7dp card; reproduce that proportion by
     * insetting the two slider views and widening the gap between them.
     */
    private static void narrowSliderTracks(View panel) {
        float density = panel.getResources().getDisplayMetrics().density;
        int cardPx = Math.round(LS_CARD_HEIGHT_DP * density);
        boolean landscape = panel.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // The classic QS viewport takes 186dp: a taller card would have to be un-clipped, which
        // also frees the tile grid sitting just below it inside the same viewport, so the card is
        // capped at the viewport height instead.  Kept explicit so the intent is not lost again.
        setHeight(panel.findViewById(idConnectivitySliderWrapper), cardPx);
        setHeight(panel.findViewById(idConnectivityTilesWrapper), cardPx);
        setHeight(panel.findViewById(idSliderWrapper), cardPx);
        View wrapper = panel.findViewById(idSliderWrapper);
        if (!(wrapper instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout ll = (android.widget.LinearLayout) wrapper;
        int n = ll.getChildCount();
        if (n < 2) return;
        // the slider column is [brightness, gap, volume]; the old design drew the two icons at
        // different sizes, so each icon view gets its own size back
        // NOTE: the floating brightness bar (the mirror) gets this same size for free -- measured
        // 71x71 on both the control-centre slider and the bar's own copy, so nothing to sync there.
        setSize(ll.getChildAt(0).findViewById(idSliderIcon),
                Math.round(LS_BRIGHTNESS_ICON_DP * density));
        setSize(ll.getChildAt(n - 1).findViewById(idSliderIcon),
                Math.round(LS_VOLUME_ICON_DP * density));
        int w = ll.getWidth();
        if (w <= 0) return;
        int track = Math.round(w * LS_SLIDER_TRACK_RATIO);
        int outer = (w - 2 * track) / 3;
        if (track <= 0 || outer <= 0) return;
        // the old proportion, trimmed by the requested amount on BOTH sides, so the track grows
        // by twice that
        int vInset = Math.max(0, Math.round(ll.getHeight() * LS_SLIDER_V_INSET_RATIO)
                - Math.round(LS_SLIDER_V_INSET_TRIM_DP * density));
        float radius = track * LS_SLIDER_RADIUS_RATIO;
        int last = n - 1;
        for (int i = 0; i < n; i++) {
            View child = ll.getChildAt(i);
            android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (!(lp instanceof android.widget.LinearLayout.LayoutParams)) continue;
            android.widget.LinearLayout.LayoutParams llp =
                    (android.widget.LinearLayout.LayoutParams) lp;
            if (i == 0 || i == last) {
                boolean changed = false;
                if (i == 0 && llp.getMarginStart() != outer) {
                    llp.setMarginStart(outer);
                    changed = true;
                }
                if (i == last && llp.getMarginEnd() != outer) {
                    llp.setMarginEnd(outer);
                    changed = true;
                }
                if (llp.topMargin != vInset || llp.bottomMargin != vInset) {
                    llp.topMargin = vInset;
                    llp.bottomMargin = vInset;
                    changed = true;
                }
                if (changed) child.setLayoutParams(llp);
                applySliderRadius(child, radius);
            } else if (llp.width != outer) {
                llp.width = outer;
                child.setLayoutParams(llp);
            }
        }
    }

    /** The stock slider uses qs_corner_radius (18dp); the old track was much less round. */
    private static void applySliderRadius(View slider, float radius) {
        Float cached = sSliderRadius.get(slider);
        if (cached != null && Math.abs(cached - radius) < 0.5f) return;
        try {
            Class<?> utils = XposedHelpers.findClass(
                    "com.flyme.systemui.utils.SystemUICommonUtils",
                    slider.getContext().getClassLoader());
            XposedHelpers.callStaticMethod(utils, "setViewSmoothCorner", slider, radius);
            sSliderRadius.put(slider, radius);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
    private static Object sShadeWindowCtrl;

    /** The shade window controller: setBackgroundBlurRadius() is the old design's blur path. */
    private static Object shadeWindowController(ClassLoader cl) {
        if (sShadeWindowCtrl != null) return sShadeWindowCtrl;
        try {
            Class<?> dep = XposedHelpers.findClass("com.android.systemui.Dependency", cl);
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.NotificationShadeWindowController", cl);
            Object inst = XposedHelpers.callStaticMethod(dep, "get", cls);
            if (inst != null) sShadeWindowCtrl = inst;
            return inst;
        } catch (Throwable t) {
            log("shade window controller unavailable: " + t);
            return null;
        }
    }

    private static float MathUtilsLerp(float start, float stop, float amount) {
        return start + (stop - start) * amount;
    }

    private static void setSize(View v, int px) {
        if (v == null) return;
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || (lp.width == px && lp.height == px)) return;
        lp.width = px;
        lp.height = px;
        v.setLayoutParams(lp);
    }

    private static void setHeight(View v, int px) {
        if (v == null) return;
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || lp.height == px) return;
        lp.height = px;
        v.setLayoutParams(lp);
    }

    private static float flowFloat(Object interactor, String getter) {
        try {
            Object flow = XposedHelpers.callMethod(interactor, getter);
            Object v = XposedHelpers.callMethod(flow, "getValue");
            if (v instanceof Number) return ((Number) v).floatValue();
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    /**
     * The cover may only be opaque while the shade is pulled down; on the lock screen it has to
     * stay invisible.  "isAnyExpanded" is the authoritative flag; the numeric expansion is only
     * used to fade the cover in while the shade is being dragged.
     */
    private static void applyCoverAlpha() {
        View cover = sCover;
        if (cover == null) return;
        // The cover is installed once and the poller is the only thing that runs on every frame, so
        // this is where a theme switch repaints the plate (the card fills and the header ink follow
        // sNightUi on their own; the two geometry-only resources are theme-independent).
        refreshNight(cover.getResources());
        // The notification rows carry the surface colour they were handed at first sight, so a
        // day/night switch has to rebuild them (see refreshNotificationRowTheme) -- and before
        // applyCardTint() replays that record further down.
        if (!sRowsNightInit) {
            sRowsNight = sNightUi;
            sRowsNightInit = true;
        } else if (sRowsNight != sNightUi) {
            sRowsNight = sNightUi;
            refreshNotificationRowTheme();
            // ... and the panel's ink: SystemUI recolours the header (clock / date / 收纳箱 button) and
            // the connectivity card's labels only from callbacks that a uiMode switch never fires.
            reInkForTheme();
            refreshEmptyShadeInk();
        }
        // Theme repaint for the control centre's cards, deliberately BEFORE any of the early returns
        // below: the lock screen's pull-down hands the panel to driveClassicLockPull() and returns
        // from here for as long as that panel is off the floor, so a repaint that lived in the
        // branches would miss exactly the panel the report is about (独立面板控制中心 深色模式
        // 通信/亮度与音量 卡片).  Both panels' cards are visited -- see repaintQsCards / sQsCards.
        repaintQsCards(false);
        if (sFilterPanel != null && sFilterPanelNight != sNightUi) {
            // The intercepted-notification box keeps the colour it was last given -- its own pass does
            // not run on a theme switch -- so it is repainted here, the same way the scrim is.
            log("8 filter panel repainted for night=" + sNightUi);
            applyFilterPanelColors(sFilterPanel);
        }
        if (!sPrivacyChips.isEmpty()) {
            // The chip's label ink follows the plate, not the vendor's passes: the chips are normally
            // created (white) while the shade is still closed, and their own colour pass never runs
            // again on this build, so the poller is what forces them black when the plate comes up and
            // what hands them back to the system when the plate goes away (shade closed, lock screen).
            applyPrivacyChipInks();
        }
        if (sCoverScrim != null && sCoverScrimNight != sNightUi) {
            sCoverScrimNight = sNightUi;
            sCoverScrim.setColor(coverScrimColor());
            log("cover scrim repainted 0x" + Integer.toHexString(coverScrimColor()));
        }
        Object mgr = sManager;
        float value = -1f;
        boolean anyExpanded = false;
        float anyFlow = -1f, shadeFlow = -1f, qsFlow = -1f;
        if (mgr != null) {
            try {
                Object si = XposedHelpers.getObjectField(mgr, "shadeInteractor");
                Object b = XposedHelpers.callMethod(si, "isAnyExpanded");
                anyExpanded = Boolean.TRUE.equals(XposedHelpers.callMethod(b, "getValue"));
                anyFlow = flowFloat(si, "getAnyExpansion");
                shadeFlow = flowFloat(si, "getShadeExpansion");
                qsFlow = flowFloat(si, "getQsExpansion");
                value = Math.max(anyFlow, shadeFlow);
                value = Math.max(value, qsFlow);
            } catch (Throwable t) {
                if (!sExpansionLogged) {
                    sExpansionLogged = true;
                    log("shade interactor unavailable: " + t);
                }
            }
        }
        Object npvc = sPanelController;
        float panel = -1f;
        float panelH = -1f, maxPanelH = -1f;
        if (npvc != null) {
            try {
                Object v = XposedHelpers.callMethod(npvc, "getExpandedFraction");
                if (v instanceof Number) panel = ((Number) v).floatValue();
                Object h = XposedHelpers.getObjectField(npvc, "mExpandedHeight");
                if (h instanceof Number) panelH = ((Number) h).floatValue();
                Object m = XposedHelpers.callMethod(npvc, "getMaxPanelHeight");
                if (m instanceof Number) maxPanelH = ((Number) m).floatValue();
            } catch (Throwable ignored) {
            }
        }
        boolean kg = keyguardShowing();
        // The veil follows the notification shade's own expansion -- mExpandedHeight / the shade
        // window (getExpandedFraction()), which is the "full pull" the two COVER_* constants are
        // fractions of -- and NOT the shade-interactor flows.
        //
        // getQsExpansion()/getAnyExpansion() are 1.0 as soon as the QS/control-centre page is the
        // one being shown, even while the panel itself is still at 0px: with notifications the shade
        // opens on the notification list and both flows track the drag, but with an empty
        // notification list Flyme opens the control-centre page straight away, so qs/any are 1.0
        // from the first touch.  Taking max(value, panel) then made the white mask fully opaque on
        // the first pixel of the pull (logged: d=10px panel=0.0 qs=1.0 -> a=1.0).  The panel's own
        // fraction is correct in both cases (d=10 panel=0.0, d=120 panel=0.0376), so it is the only
        // drive for the mask.
        float drive = panel;
        if (!(drive >= 0f)) {
            // Startup / a vendor update that renamed getExpandedFraction: fall back to the shade's
            // own expansion flow, never to qsExpansion (see above).  NaN falls through to 0.
            drive = shadeFlow >= 0f ? shadeFlow : 0f;
        }
        // Mask ramp: nothing below COVER_MIN_EXPANSION (1% of a full pull, 32px), a straight line to
        // full white at COVER_MASK_FULL_AT (7%, 223px), 1 after that.  The blur runs on its own,
        // slower ramp (COVER_BLUR_FULL_AT), so the white is fully up first.
        float a = 0f;
        if (drive > COVER_MIN_EXPANSION) {
            float ramp = (drive - COVER_MIN_EXPANSION) / (COVER_MASK_FULL_AT - COVER_MIN_EXPANSION);
            a = Math.max(0f, Math.min(1f, ramp));
        }
        // The lock screen keeps the stock look -- but ONLY while its shade is tucked away.  The
        // keyguard flag is unreliable: it flips back to true once an unlocked shade settles, which
        // used to release the cover (and the mask) exactly on release, so the white vanished as soon
        // as the finger let go.  Requiring a closed shade makes the release happen only on the real
        // lock screen, where nothing is expanded anyway.
        //
        // Exception: while the control centre is being pulled down on the lock screen the module
        // takes the transition over (see driveClassicLockPull) -- that path owns the cover, the veil
        // and the CC panel for as long as its panel is off the floor.
        if (kg && driveClassicLockPull(cover)) return;
        // The lock screen can go away while its control centre is open (unlock, camera, ...): hand
        // the views back before the unlocked shade starts using them.
        if (!kg && sLockPullOn) endClassicLockPull(cover, sCenter);
        // ... and if it was merely idle (no pull in progress), the idle branch below clipped the
        // control-centre panel to zero height and pushed its content off-screen every tick.  That
        // state has to be handed back when the keyguard goes away: otherwise it stays on the CC
        // panel after unlocking and the first unlocked pull-down -- exactly the no-notification
        // case, where the vendor opens the CC page instead of the notification list -- has its
        // content clipped away, leaving only the module's white veil on screen.
        if (!kg && sCcIdleClipped) {
            sCcIdleClipped = false;
            if (sCcPanel != null) sCcPanel.setClipBounds(null);
            if (sCcContent != null && sCcContent.getTranslationY() != 0f) {
                sCcContent.setTranslationY(0f);
            }
            log("unlock: lock-screen CC clip/translation released");
        }
        if (kg) {   // the lock screen never shows the module's cover/mask
            cover.setVisibility(View.INVISIBLE);
            ViewGroup kgRoot = cover.getRootView() instanceof ViewGroup
                    ? (ViewGroup) cover.getRootView() : null;
            // Keep the control centre clipped away while the lock screen is idle: the vendor shows
            // its panel on the first touch event, one frame before this poller can put the clip on
            // it, and that single unclipped frame is what flashed the whole control centre at the
            // start of a pull.  Landscape keeps the vendor's animation, so nothing is clipped there.
            if (kgRoot != null && sCenter != null && ensureLockViews(kgRoot, sCenter)) {
                if (lockPortrait()) {
                    applyCcReveal();        // h == 0 -> a zero-height clip
                    sCcIdleClipped = true;
                } else if (sCcPanel != null) {
                    sCcPanel.setClipBounds(null);
                    sCcIdleClipped = false;
                }
            }
            // A collapse just finished: keep the lock screen's own values up until the vendor's
            // OCCLUDED->LOCKSCREEN fade has run its course (it would otherwise blink the panel out
            // again and leave the white veil on an empty screen for a few frames).
            if (android.os.SystemClock.uptimeMillis() < sLockRestoreUntil) {
                setAlpha(sLockClock, 1f);
                setAlpha(sLockLockIcon, 1f);
                setAlpha(sLockStatusBar, 1f);
                setAlpha(sLockBottomArea, 1f);
                if (sLockPanel != null && sLockPanel.getAlpha() < 1f) sLockPanel.setAlpha(1f);
                if (sLockStack != null && sLockStack.getTranslationY() != 0f) sLockStack.setTranslationY(0f);
            }
            hideWallpaperLayers(kgRoot, false);
            if (MASK_ON_MIRROR) clearMirrorMask();
            applyCardTint(false);   // lock screen keeps the stock card fill
            applyMediaCardTint(mediaCard(kgRoot), false);
            sShadeOpen = false;
            // No drag on the lock screen: release the blur so the stock look comes back.
            sBlurDrive = 0f;
            sMaskAlpha = 0f;
            driveCoverBlur(cover);
            driveCoverBlurAlpha(cover, 255);
            driveShadeWindowBlur();
            long t0 = android.os.SystemClock.uptimeMillis();
            if (t0 - sLastAlphaLog > 1500) {
                sLastAlphaLog = t0;
                log("keyguard idle: cover released, wallpaper layers restored");
            }
            return;
        }
        boolean open = a > 0.005f;   // the veil is up as soon as the shade has really moved
        // The cover carries the blur region, so it has to be visible from the first pixel of the
        // pull: showing it only once the mask starts (which is what `open` means) is what made the
        // backdrop jump from sharp to fully blurred at 262px.
        boolean dragging = drive > 0.0015f;   // ~5px of travel
        sShadeOpen = open || dragging;
        if (sShadeOpen && sPanelController != null) {
            // The chip's x offset is the cutout inset the vendor writes; re-measure it while the shade
            // is up, so the row stays flush with the notification column even if a layout pass (or a
            // rotation) goes past updatePadding.  A no-op once the value is right.
            alignPrivacyChipRow(sPanelController);
        }
        // Publish the drag and the mask alpha for the blur handling below.  The blur ramp follows
        // the RAW drag (published as sBlurDrive) and restarts from 0 whenever the pull ends, so a
        // fresh pull-down never inherits the previous radius.
        sBlurDrive = dragging ? drive : 0f;
        sMaskAlpha = a;
        // Both blur layers are ramped off the same curve: the cover's own BackgroundBlurDrawable
        // (the layer SurfaceFlinger reports as the window's blur region) and the shade window's
        // radius.  Neither may wait for the white mask -- see COVER_BLUR_FULL_AT.
        // The brightness mirror page is the exception: the vendor fades the panel (and with it the
        // cover and its white scrim) to alpha 0 there, so nothing of the module is visible -- but the
        // cover's blur region still is, which blurs the desktop behind the floating bar.  On that
        // page both layers are driven to MIRROR_BLUR_PX/MIRROR_BLUR_ALPHA instead, and the normal ramp
        // returns on hideMirror(), i.e. when the finger is released (see brightnessMirrorUp()).
        boolean mirrorPage = brightnessMirrorUp();
        if (mirrorPage) sBlurDrive = 0f;
        driveCoverBlur(cover);
        driveCoverBlurAlpha(cover, mirrorPage ? MIRROR_BLUR_ALPHA : 255);
        driveShadeWindowBlur();
        if (mirrorPage && !sMirrorBlurLogged) {
            sMirrorBlurLogged = true;
            log("9 mirror page: backdrop blur forced to " + sCoverBlurPx + "px/alpha "
                    + sCoverBlurAlphaPx + " (window " + wantShadeBlurPx() + "px)");
        } else if (!mirrorPage) {
            sMirrorBlurLogged = false;
        }
        // Hide the wallpaper layers from the very first frame of the pull-down, otherwise the
        // animation still shows the wallpaper (see hideWallpaperLayers).
        ViewGroup shadeRoot = cover.getRootView() instanceof ViewGroup
                ? (ViewGroup) cover.getRootView() : null;
        hideWallpaperLayers(shadeRoot, HIDE_WALLPAPER_LAYERS && (value > 0.001f || panel > 0.001f));
        if (!dragging) {
            cover.setVisibility(View.INVISIBLE);
            if (MASK_ON_MIRROR) clearMirrorMask();
            applyCardTint(false);   // shade closed: the rows go back to the vendor's own fill
            applyMediaCardTint(mediaCard(shadeRoot), false);
            // ... and the control centre's cards are kept on the current theme by the repaint at the
            // top of this method (it runs on every tick, whichever branch is taken below).
        } else {
            cover.setVisibility(View.VISIBLE);
            // Never fully transparent while the pull is in progress: see COVER_MIN_ALPHA.
            cover.setAlpha(a > COVER_MIN_ALPHA ? a : COVER_MIN_ALPHA);
            if (MASK_ON_MIRROR) applyMirrorMask(shadeRoot, a);
            // Cards are replayed white for as long as the classic cover is up, so a notification
            // that arrives while the shade is open cannot come back as a 70% blur veil.
            applyCardTint(open);
            // ... and the media player card is the same surface, on its own class (hook 7m): the
            // vendor rebuilds it on every media / live-notification change, so it is re-applied
            // here as well, on the same "veil is up" gate the rows use.
            applyMediaCardTint(mediaCard(shadeRoot), open);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastAlphaLog > 1500) {
            sLastAlphaLog = now;
            log("gate panel=" + panel + " h=" + panelH + " max=" + maxPanelH
                    + " flows=" + value + " anyExpanded=" + anyExpanded + " keyguard=" + kg
                    + " -> " + a + " blur=" + sCoverBlurPx + "px"
                    + " coverA=" + cover.getAlpha() + " vis=" + cover.getVisibility()
                    + " scrimA=" + (android.graphics.Color.alpha(coverScrimColor())
                    * Math.round(255f * cover.getAlpha()) / 255));
        }
    }

    /**
     * The shade paints the WALLPAPER as its own backdrop (lock_wallpaper_mirror plus the two
     * wallpaper scrims inside keyguard_background_frame), so a blur behind the shade can only ever
     * sample the wallpaper.  To get the old design's "blurred interface underneath", those layers
     * are hidden while the shade is open, which lets the app behind become the shade's backdrop --
     * and the blur drawable on the cover then samples the app.  They are restored as soon as the
     * shade closes, so the lock screen and the idle wallpaper are untouched.
     */

    /** Experiment path: white mask carried by the wallpaper mirror itself. */
    private static void applyMirrorMask(ViewGroup root, float alpha) {
        if (root != null && sMirror == null) sMirror = root.findViewById(idWallpaperMirror);
        View v = sMirror;
        if (v == null) return;
        if (!sMirrorMaskApplied) {
            sMirrorMaskApplied = true;
            sMirrorMaskOrigBg = v.getBackground();
            sMirrorMaskOrigAlpha = v.getAlpha();
            sMirrorMaskOrigVisibility = v.getVisibility();
            try {
                // a SurfaceView only lets the window through when its surface is translucent
                if (v instanceof android.view.SurfaceView) {
                    ((android.view.SurfaceView) v).getHolder()
                            .setFormat(android.graphics.PixelFormat.TRANSLUCENT);
                }
            } catch (Throwable ignored) {
            }
            v.setBackground(new ColorDrawable(coverScrimColor()));
        }
        if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        float want = Math.max(0f, Math.min(1f, alpha));
        if (v.getAlpha() != want) v.setAlpha(want);
        sSavedVisibility.remove(v);   // hideWallpaperLayers must not fight this
    }

    private static void clearMirrorMask() {
        View v = sMirror;
        if (v == null || !sMirrorMaskApplied) return;
        sMirrorMaskApplied = false;
        v.setBackground(sMirrorMaskOrigBg);
        v.setAlpha(sMirrorMaskOrigAlpha);
        if (v.getVisibility() != sMirrorMaskOrigVisibility) {
            v.setVisibility(sMirrorMaskOrigVisibility);
        }
    }

    private static void hideWallpaperLayers(ViewGroup root, boolean hide) {
        if (root != null && sMirror == null) {
            sMirror = root.findViewById(idWallpaperMirror);
            sFgShade = root.findViewById(idWallpaperForegroundShade);
            sBlurBg = root.findViewById(idWallpaperBlurBg);
        }
        // wallpaper_foreground_shade is deliberately NOT hidden: it is the vendor's own scrim over
        // the wallpaper and is repurposed as the white mask (applyWallpaperMask).
        for (View v : new View[]{sMirror, sFgShade, sBlurBg}) {
            if (v == null) continue;
            if (hide) {
                if (sSavedVisibility.get(v) == null) sSavedVisibility.put(v, v.getVisibility());
                if (v.getVisibility() != View.INVISIBLE) v.setVisibility(View.INVISIBLE);
            } else {
                Integer was = sSavedVisibility.remove(v);
                if (was != null && v.getVisibility() != was) v.setVisibility(was);
            }
        }
    }

    /**
     * The white mask is applied to the VENDOR's own wallpaper scrim instead of a view we add: it
     * already sits over the wallpaper inside keyguard_background_frame, so with the wallpaper mirror
     * hidden it is exactly "a white veil over whatever is behind the shade".  Only its background
     * and alpha are touched, both saved once and restored whenever the module is not driving it
     * (shade closed / lock screen), so the lock screen keeps its stock look.
     */
    private static void applyWallpaperMask(ViewGroup root, float alpha) {
        if (root != null && sFgShade == null) {
            sFgShade = root.findViewById(idWallpaperForegroundShade);
        }
        View v = sFgShade;
        if (v == null) return;
        if (!sMaskApplied) {
            sMaskApplied = true;
            sMaskOrigBackground = v.getBackground();
            sMaskOrigAlpha = v.getAlpha();
            sMaskOrigVisibility = v.getVisibility();
            v.setBackground(new ColorDrawable(coverScrimColor()));
        }
        // the vendor keeps this layer GONE most of the time, so it has to be shown explicitly
        if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        float want = Math.max(0f, Math.min(1f, alpha));
        if (v.getAlpha() != want) v.setAlpha(want);
    }

    private static void clearWallpaperMask() {
        View v = sFgShade;
        if (v == null || !sMaskApplied) return;
        sMaskApplied = false;
        v.setBackground(sMaskOrigBackground);
        v.setAlpha(sMaskOrigAlpha);
        if (v.getVisibility() != sMaskOrigVisibility) v.setVisibility(sMaskOrigVisibility);
    }



    /* -------- notification card colour, while the shade is open only -------- */

    /* ================= classic lock-screen pull-down ================= */

    /*
     * The lock screen's pull-down is a different gesture from the unlocked shade's: it expands
     * Flyme's own control-centre panel ({@link #sCenter}, CenterController) instead of
     * NotificationPanelView, and the vendor turns that expansion into "the whole lock screen shrinks
     * and blurs, then the control centre fades in":
     *
     *   NotificationPanelViewController#onControlCenterBlurChanged(i)  i from the CC's expansion
     *     -> setPanelViewScaleByBlur(i): updateScale(1 - i*0.15/300) on the keyguard status bar and
     *        the notification container, and a RenderEffect flyme blur on the whole panel
     *   CenterController#createExpandAnimator()  at fraction 0.1
     *     -> behind plate alpha 0->1 (300 ms), CC content alpha 0->1 (350 ms), CC scale 0.8->1 (500 ms)
     *
     * The old design did none of that.  It held the lock screen still, faded the clock out over the
     * first eighth of the screen, faded the status bar / bottom buttons / hint over the first fifth,
     * brought a white veil up behind the content, and only then let the control centre slide down
     * from the top while the notification column was pushed off the bottom.  Those ramps are below.
     *
     * <p>Everything is driven from the CC panel's own expanded height, which follows the finger 1:1
     * (measured: 300 px of finger travel = 300 px of {@code mExpandedHeight}), so the transition is
     * drag-driven exactly like the old one -- not a time-based animation that snaps open.
     *
     * <p>Fed by {@link #applyCoverAlpha}'s poller, which is the module's only per-frame hook.  The
     * vendor's own versions of the same effects are neutralised in {@link #hookClassicLockPull}: the
     * blur/scale call is forced to 0, the CC content is pinned to alpha/scale 1, and the CC's own
     * background plate is held invisible so the module's white veil is what covers the wallpaper.
     */
    /**
     * The clock's fade: gone after the first eighth of the screen.
     *
     * <p>The padlock above the clock fades on this same ramp.  It is <em>not</em> part of the clock's
     * view: it is {@code com.flyme.systemui.facerecognition.FaceRecognitionAnimationView}
     * ({@code id/face_animator_view}), the vendor's Lottie player that shows the locked-padlock clip
     * ({@code assets/face_locked_new.json}) while face authentication runs.  It is a sibling of
     * {@code keyguard_status_view} inside {@code notification_panel}, so the alpha the module applies
     * to the clock never reaches it -- and the vendor does not fade it either: on the device the
     * clock was almost gone while the padlock was still fully white (see issues.png).  The old design
     * faded the two together, so {@link #driveClassicLockPull} drives the padlock with this same
     * {@code clockA} value; {@link #sLockLockIcon} is that view.
     */
    private static final float LOCK_CLOCK_FADE = 1f / 8f;   // clock gone after 1/8 of the screen
    private static final float LOCK_BARS_FADE = 1f / 5f;    // bars / buttons / hint gone after 1/5
    private static final float LOCK_VEIL_START = 0.85f / 8f; // veil starts as the clock disappears
    private static final float LOCK_VEIL_FULL = 0.21f;       // ... and is full just past 1/5
    /**
     * Where the control centre starts coming down: 0.30 of the screen.  The reference device's own
     * content appeared at 0.369 x H; this build's notification column sits higher (825 px = 0.258 H
     * against 706 px = 0.315 H), so the same end state -- content in place and column just off the
     * screen at the same time -- needs the content to start a little earlier.  It is still well past
     * the veil (0.21), which is the order the user described.
     */
    private static final float LOCK_CC_START = 0.30f;
    /**
     * Longest the collapse animation may take after the finger leaves the screen.  The vendor uses
     * 350 ms, or 500 ms when "control centre full animation" is enabled, and the user found that too
     * slow compared with the old panel; this is the cap the collapse animator is built with.
     */
    private static final long LOCK_CC_COLLAPSE_MS = 250L;
    /**
     * How the notification column is pushed.  The column and the control-centre content move as one
     * piece: from {@link #LOCK_CC_START} on, the column's offset is the content's own offset plus
     * {@code gap0 = H - base - extent}, so the page visibly pushes the cards and they come back with
     * it on the way up (the user's description of the old behaviour: "控制中心整页把通知顶到屏幕下面
     * 去 ... 被顶到屏幕下的通知随着上滑回到原来的位置").  Before the content appears the column has
     * already drifted by that same gap0, slowly, so it starts moving on the first pixel like the old
     * one did.  At full pull the column is exactly at the bottom edge (base + gap0 + extent = H).
     */
    private static final float LOCK_STACK_PUSH = 0.76f;   // fallback only: used when base/extent unknown

    /**
     * Lock-screen content of the pull-down, resolved once from the shade root.
     *
     * <p>{@link #sLockLockIcon} is the padlock above the clock, i.e. the vendor's face-unlock
     * animation view ({@code id/face_animator_view}); the clock's own alpha cannot fade it because
     * the two are siblings, not parent and child -- see {@link #LOCK_CLOCK_FADE}.
     */
    private static volatile View sLockClock, sLockStatusBar, sLockBottomArea, sLockStack, sLockPanel,
            sLockLockIcon;
    /** The control-centre panel and the pieces of it the animation drives. */
    private static volatile View sCcPanel, sCcContent, sCcBehind, sCcQs, sCcHeader, sCcStatusBar,
            sCcTilePage;
    /** True while this transition owns the lock screen's CC panel (and {@link #applyCoverAlpha} the cover). */
    private static volatile boolean sLockPullOn = false;
    /**
     * True while the idle lock screen's zero-height clip is on {@link #sCcPanel}.  The clip (and the
     * content's off-screen translation that {@link #applyCcReveal} applies with it) must be handed
     * back when the keyguard goes away -- see the unlock clean-up in {@link #applyCoverAlpha}.
     */
    private static volatile boolean sCcIdleClipped = false;
    /** Reused clip rectangle for the CC panel's reveal (setClipBounds copies it). */
    private static volatile android.graphics.Rect sCcClipRect;
    /** Last reveal height handed to the CC panel; only used for the log line. */
    private static volatile float sLockReveal = -1f;
    private static volatile boolean sLockPullLogged = false;
    /** Last expanded height seen, and when it last moved -- the poll rate follows this. */
    private static volatile float sLockLastH = -1f;
    private static volatile long sLockLastMove = 0L;
    /**
     * Until this timestamp the lock screen's own views are re-asserted after a CC collapse.  The
     * vendor's OCCLUDED->LOCKSCREEN transition keeps fading {@code notification_panel} in for a few
     * hundred ms after the panel is gone; without this the lock screen sat blank behind the white
     * veil until that fade finished.
     */
    private static volatile long sLockRestoreUntil = 0L;
    /**
     * The finger's y while the control centre's TouchHandler is processing a live touch event, -1
     * otherwise.  Used to rebuild the 1:1 drag height the vendor compresses away (see the
     * setExpandedHeightInternal hook).
     */
    private static volatile float sTouchY = -1f;

    private static float clamp01(float f) {
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    private static int lockId(View v, String name) {
        return v.getResources().getIdentifier(name, "id", "com.android.systemui");
    }

    /** False while the screen is off / dozing -- the lock-screen transition must not outlive that. */
    private static boolean powerInteractive(View v) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    v.getContext().getSystemService(android.content.Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private static float centerExpandedHeight() {
        Object c = sCenter;
        if (c == null) return -1f;
        try {
            Object v = XposedHelpers.getObjectField(c, "mExpandedHeight");
            if (v instanceof Number) return ((Number) v).floatValue();
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    /**
     * True while the classic lock-screen transition is the one that should run: lock screen, portrait
     * and the control centre actually off the floor.  The neutralising hooks use the same test, so in
     * landscape -- where the vendor's own animation is left alone -- none of it is forced.
     */
    private static boolean classicLockPullArmed() {
        if (!keyguardShowing() || centerExpandedHeight() <= 0.5f) return false;
        return lockPortrait();
    }

    /** The lock-screen transition is portrait only; landscape keeps the vendor's animation. */
    private static boolean lockPortrait() {
        View c = sCover;
        if (c == null) return true;
        try {
            return c.getResources().getConfiguration().orientation
                    != android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Resolve the views the lock-screen transition needs.  Ids come from the live resources because
     * this build's ids are not the ones the rest of the module carries (which were read off the same
     * APK, but keeping the two apart makes a ROM update fail visibly instead of silently).
     */
    private static boolean ensureLockViews(View root, Object center) {
        if (sLockClock == null || sLockClock.getParent() == null || sLockClock.getRootView() != root) {
            sLockClock = root.findViewById(lockId(root, "keyguard_status_view"));
            sLockStatusBar = root.findViewById(lockId(root, "keyguard_header"));
            sLockBottomArea = root.findViewById(lockId(root, "keyguard_bottom_area"));
            sLockStack = root.findViewById(lockId(root, "notification_stack_scroller"));
            sLockPanel = root.findViewById(lockId(root, "notification_panel"));
            // The padlock above the clock: Flyme's face-recognition Lottie view, which draws the
            // locked-padlock clip and is what the user sees as "the lock icon" on the lock screen.
            // It lives outside keyguard_status_view, hence the separate hook-up here.
            sLockLockIcon = root.findViewById(lockId(root, "face_animator_view"));
            sCcPanel = null;
        }
        if (sCcPanel == null) {
            try {
                Object p = XposedHelpers.getObjectField(center, "mControlCenterPanelView");
                if (p instanceof View) sCcPanel = (View) p;
                else return false;
                Object content = XposedHelpers.getObjectField(center, "mCenterContent");
                if (content instanceof View) sCcContent = (View) content;
                Object behind = XposedHelpers.getObjectField(center, "mBehindView");
                if (behind instanceof View) sCcBehind = (View) behind;
                Object qs = XposedHelpers.getObjectField(center, "mQSContainer");
                if (qs instanceof View) sCcQs = (View) qs;
                Object header = XposedHelpers.getObjectField(center, "mHeader");
                if (header instanceof View) sCcHeader = (View) header;
            } catch (Throwable t) {
                log("lock pull: CC views unavailable: " + t);
                return false;
            }
            if (sCcPanel != null) {
                sCcStatusBar = sCcPanel.findViewById(lockId(sCcPanel, "center_status_bar_layout"));
                sCcTilePage = sCcPanel.findViewById(lockId(sCcPanel, "tile_page"));
            }
        }
        return sCcPanel != null && sCcContent != null;
    }

    /**
     * Drive the classic lock-screen transition from the CC panel's expanded height.
     *
     * @return true while this transition is in charge (the caller must not run the lock-screen idle
     *         release), false when there is nothing to drive -- in which case the caller restores the
     *         idle lock screen.
     */
    private static boolean driveClassicLockPull(View cover) {
        Object c = sCenter;
        if (c == null) return false;
        float h = centerExpandedHeight();
        if (h <= 0.5f) {
            if (sLockPullOn) endClassicLockPull(cover, c);
            return false;
        }
        View root = cover.getRootView() instanceof ViewGroup ? (ViewGroup) cover.getRootView() : null;
        if (root == null || !ensureLockViews(root, c)) return false;
        int H = root.getHeight();
        if (H <= 0) return false;
        int W = root.getWidth();
        // Landscape keeps the vendor's animation: this transition is calibrated against the portrait
        // lock screen (the CC panel and the clock sit differently in landscape) and the old design's
        // ramps are all "fractions of the screen's height".
        if (W > H) {
            if (sLockPullOn) endClassicLockPull(cover, c);
            return false;
        }
        // Screen off / AOD: stop driving and let the stock state come back.  Do NOT stop just because
        // the CC panel went invisible: during the collapse the vendor hides it as soon as the fling
        // starts, and ending here left the lock screen mid-restore while the vendor's own
        // LOCKSCREEN transition was still fading the notification panel in -- one blank white frame.
        if (!powerInteractive(cover)) {
            if (sLockPullOn) endClassicLockPull(cover, c);
            return false;
        }

        float clockEnd = H * LOCK_CLOCK_FADE;
        float barsEnd = H * LOCK_BARS_FADE;
        float veilStart = H * LOCK_VEIL_START;
        float veilFull = H * LOCK_VEIL_FULL;
        float ccStart = H * LOCK_CC_START;

        float clockA = 1f - clamp01(h / clockEnd);
        float barsA = 1f - clamp01(h / barsEnd);
        float veil = clamp01((h - veilStart) / (veilFull - veilStart));
        if (Math.abs(h - sLockLastH) > 0.4f) {
            sLockLastH = h;
            sLockLastMove = android.os.SystemClock.uptimeMillis();
        }
        float push = applyCcReveal();

        // --- lock screen content: no scale, just the old fades ---
        setAlpha(sLockClock, clockA);
        // The padlock is a sibling of the clock, not a child of it, so its own alpha has to be driven
        // as well: the vendor's FaceRecognitionAnimationView never fades itself, and leaving it at 1
        // left a white padlock hanging over the veil while the clock was already gone (issues.png).
        setAlpha(sLockLockIcon, clockA);
        setAlpha(sLockStatusBar, barsA);
        setAlpha(sLockBottomArea, barsA);
        if (sLockPanel != null && sLockPanel.getAlpha() < 1f) sLockPanel.setAlpha(1f);
        // The notification column (and the media card in it) is driven by the control-centre page --
        // see applyCcReveal(): it drifts from the first pixel, then moves 1:1 with the CC content so
        // the page pushes it off the bottom, and comes back with it on the way up.
        if (sLockStack != null && sLockStack.getTranslationY() != push) {
            sLockStack.setTranslationY(push);
        }

        // --- control centre: its reveal/clip is applied by applyCcReveal() above ---
        pinCcChrome();
        // Belt and braces for the vendor's shrink/blur: its flow only emits when the value changes,
        // so a single emission that slipped past the hook (the keyguard flag flickers while the
        // shade occludes it) leaves the panel's RenderEffect up for the rest of the drag.  Driving
        // the same call with 0 every frame also re-asserts updateScale(1) and the rows' opaque live
        // blur, and it is a no-op when nothing changed.
        if (sPanelController != null) {
            try {
                XposedHelpers.callMethod(sPanelController, "onControlCenterBlurChanged",
                        Integer.valueOf(0));
            } catch (Throwable ignored) {
            }
        }

        // --- the module's white veil, behind the content ---
        sLockPullOn = true;
        sLockReveal = push;
        cover.setVisibility(View.VISIBLE);
        float a = Math.max(veil, COVER_MIN_ALPHA);
        if (cover.getAlpha() != a) cover.setAlpha(a);
        // The backdrop is hazed from the first pixel of the pull (the old one was visibly blurred
        // before the white veil had any weight), so the blur follows the raw drag, not the veil.
        sBlurDrive = clamp01(h / veilFull);
        sMaskAlpha = veil;
        driveCoverBlur(cover);
        driveCoverBlurAlpha(cover, 255);
        driveShadeWindowBlur();

        if (!sLockPullLogged) {
            sLockPullLogged = true;
            log("lock pull: classic transition engaged (H=" + H + " clockEnd=" + Math.round(clockEnd)
                    + " barsEnd=" + Math.round(barsEnd) + " veil=" + Math.round(veilStart) + ".."
                    + Math.round(veilFull) + " cc=" + Math.round(ccStart) + " padlock="
                    + (sLockLockIcon != null ? "fading with the clock" : "NOT FOUND") + ")");
        }
        return true;
    }

    /**
     * Reveal the control centre from the top, the way the old panel did: its content's bottom edge
     * follows the drag 1:1 from {@link #LOCK_CC_START} up to the content's own height, so the content
     * is exactly in place when the pull is complete.  Measured on the reference device with 10 px
     * steps: the auto-brightness tile's bottom edge sat at {@code drag - 0.369 x H} for every step
     * from 830 to 1600.  The vendor's own slide (and the first version of this code) settled the
     * content at about 3/4 of the pull, which is what made the control centre look like it was
     * outrunning the notification column.
     *
     * @return the height of the revealed band (px).
     */
    private static float applyCcReveal() {
        View panel = sCcPanel;
        View content = sCcContent;
        if (panel == null || content == null) return 0f;
        int H = panel.getHeight();
        int W = panel.getWidth();
        if (H <= 0 || W <= 0) return 0f;
        float h = centerExpandedHeight();
        float start = H * LOCK_CC_START;
        float span = Math.max(1f, H - start);
        float extent = ccContentExtent(H);
        float base = notificationBase(H);
        // gap0: where the column has to sit when the control centre's content starts, so that the two
        // of them -- content (extent) and column (gap0 + extent) -- just fill the screen at full pull.
        float gap0 = Math.max(0f, H - base - extent);
        float p = clamp01((h - start) / span);
        float e = p * extent;
        // The column is pushed by the page: from start on it keeps the gap the old panel had between
        // the page edge and the first card, and moves 1:1 with the content (the cards and the page go
        // down together, and come back together when the shade is swiped up).
        float push = h < start
                ? gap0 * clamp01(h / Math.max(1f, start))
                : gap0 + e;
        if (sCcClipRect == null) sCcClipRect = new android.graphics.Rect();
        sCcClipRect.set(0, 0, W, Math.round(Math.max(push, e)));
        panel.setClipBounds(sCcClipRect);
        float ty = e - extent;
        if (Math.abs(content.getTranslationY() - ty) > 0.5f) content.setTranslationY(ty);
        return push;
    }

    /**
     * The top of the first notification row at rest, in the stack's own coordinates -- the height of
     * the clock footprint that the control centre's page has to push the column past.  Read from the
     * layout (not hard-coded) so a media card or a density change keeps the end state exact.
     */
    private static float notificationBase(int H) {
        View stack = sLockStack;
        if (stack instanceof ViewGroup) {
            try {
                Object v = XposedHelpers.callMethod(stack, "getTopPadding");
                if (v instanceof Number) {
                    float f = ((Number) v).floatValue();
                    if (f > 0f && f < H * 0.9f) return f;
                }
            } catch (Throwable ignored) {
            }
            ViewGroup g = (ViewGroup) stack;
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (ch.getVisibility() == View.VISIBLE && ch.getHeight() > 0 && ch.getTop() > 0) {
                    best = Math.min(best, ch.getTop());
                }
            }
            if (best != Integer.MAX_VALUE) return best;
        }
        return 825f;
    }

    /**
     * The visible extent of the control centre's content, measured from the tile page so it follows
     * the layout (media card present or not, night/day, density).  Falls back to the old design's
     * 0.73 of the screen when nothing can be measured.
     */
    private static float ccContentExtent(int H) {
        View t = sCcTilePage;
        if (t != null && sCcContent != null) {
            try {
                int[] loc = new int[2];
                t.getLocationOnScreen(loc);
                float e = loc[1] + t.getHeight() - sCcContent.getTranslationY();
                if (e > H * 0.2f && e < H * 1.05f) return e;
            } catch (Throwable ignored) {
            }
        }
        return H * 0.73f;
    }

    private static void setAlpha(View v, float a) {
        if (v != null && Math.abs(v.getAlpha() - a) > 0.002f) v.setAlpha(a);
    }

    /** Pin the CC chrome to the state the classic panel shows: fully opaque, unscaled, no plate. */
    private static void pinCcChrome() {
        if (sCcQs != null) {
            if (sCcQs.getAlpha() < 1f) sCcQs.setAlpha(1f);
            if (sCcQs.getScaleX() != 1f) { sCcQs.setScaleX(1f); sCcQs.setScaleY(1f); }
        }
        if (sCcHeader != null) {
            if (sCcHeader.getVisibility() != View.VISIBLE) sCcHeader.setVisibility(View.VISIBLE);
            if (sCcHeader.getAlpha() < 1f) sCcHeader.setAlpha(1f);
            if (sCcHeader.getScaleX() != 1f) { sCcHeader.setScaleX(1f); sCcHeader.setScaleY(1f); }
        }
        if (sCcStatusBar != null) {
            if (sCcStatusBar.getVisibility() != View.VISIBLE) sCcStatusBar.setVisibility(View.VISIBLE);
            if (sCcStatusBar.getAlpha() < 1f) sCcStatusBar.setAlpha(1f);
            if (sCcStatusBar.getScaleX() != 1f) { sCcStatusBar.setScaleX(1f); sCcStatusBar.setScaleY(1f); }
        }
        if (sCcBehind != null) {
            try {
                XposedHelpers.callMethod(sCcBehind, "setViewAlpha", Float.valueOf(0f));
            } catch (Throwable ignored) {
            }
        }
    }

    /** The CC panel closed (or the lock screen went away): hand every view back its stock state. */
    private static void endClassicLockPull(View cover, Object center) {
        sLockPullOn = false;
        sCcIdleClipped = false;
        sLockReveal = -1f;
        sLockPullLogged = false;
        // Keep re-asserting the lock screen's own values for a moment: the vendor's occlusion
        // transition finishes its fade after the panel is gone (see sLockRestoreUntil).
        if (keyguardShowing() && lockPortrait()) {
            sLockRestoreUntil = android.os.SystemClock.uptimeMillis() + 600L;
        }
        setAlpha(sLockClock, 1f);
        // Same value the vendor's FaceRecognitionAnimationView is born with: it never touches its own
        // alpha (see FaceRecognitionAnimationView), so handing 1 back is the stock state.
        setAlpha(sLockLockIcon, 1f);
        setAlpha(sLockStatusBar, 1f);
        setAlpha(sLockBottomArea, 1f);
        if (sLockPanel != null) sLockPanel.setAlpha(1f);
        if (sLockStack != null && sLockStack.getTranslationY() != 0f) sLockStack.setTranslationY(0f);
        // Closed again on the lock screen: keep the panel clipped away (see the idle branch) so the
        // vendor cannot flash it on the next first touch; hand it back entirely once the lock screen
        // is gone or the device is in landscape.
        if (sCcPanel != null) {
            if (keyguardShowing() && lockPortrait()) {
                if (sCcClipRect == null) sCcClipRect = new android.graphics.Rect();
                sCcClipRect.set(0, 0, sCcPanel.getWidth(), 0);
                sCcPanel.setClipBounds(sCcClipRect);
            } else {
                sCcPanel.setClipBounds(null);
            }
        }
        if (sCcContent != null && sCcContent.getTranslationY() != 0f) sCcContent.setTranslationY(0f);
        // The stock closed state of the two chrome rows is "invisible, alpha 0"; the vendor's own
        // passes make them visible again when it opens them itself.
        if (sCcHeader != null) {
            sCcHeader.setAlpha(0f);
            sCcHeader.setVisibility(View.INVISIBLE);
        }
        if (sCcStatusBar != null) {
            sCcStatusBar.setAlpha(0f);
            sCcStatusBar.setVisibility(View.INVISIBLE);
        }
        try {
            if (center != null) {
                XposedHelpers.callMethod(center, "setAnimationAlpha", Float.valueOf(0f));
                XposedHelpers.callMethod(center, "setAnimationScale", Float.valueOf(0.8f));
                XposedHelpers.callMethod(center, "setCollapseAnimationTranslationY", Float.valueOf(0f));
            }
        } catch (Throwable ignored) {
        }
        if (sCcBehind != null) {
            try {
                XposedHelpers.callMethod(sCcBehind, "setViewAlpha", Float.valueOf(0f));
            } catch (Throwable ignored) {
            }
        }
        if (cover != null) {
            cover.setVisibility(View.INVISIBLE);
            sBlurDrive = 0f;
            sMaskAlpha = 0f;
            driveCoverBlur(cover);
        }
        log("lock pull: classic transition released");
    }

    /**
     * Neutralise the vendor's version of the same transition while it is ours to run.
     *
     * <p>{@code onControlCenterBlurChanged(i)} is the one that shrinks and blurs the lock screen; on
     * the lock screen it is fed 0, which also clears the RenderEffect and the scale and keeps the
     * notification rows' live blur opaque (its 255-i*10 term).  The CC's own fades/scales come from
     * {@code CenterController#createExpandAnimator}/#createCollapseAnimator through the setters below;
     * they are pinned to the "fully open" state so the only thing that moves the panel is this module.
     */
    private static void hookClassicLockPull(ClassLoader cl) {
        try {
            Class<?> npvc = XposedHelpers.findClass(
                    "com.android.systemui.shade.NotificationPanelViewController", cl);
            XposedBridge.hookAllMethods(npvc, "onControlCenterBlurChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // Any value the flow emits while the lock screen is up is unwanted: the module
                    // runs the transition, and 0 also clears a RenderEffect/scale left over from
                    // an earlier emission.  Portrait only -- landscape keeps the vendor's animation.
                    if (keyguardShowing() && lockPortrait()) {
                        param.args[0] = Integer.valueOf(0);
                    }
                }
            });
            log("12a hook NotificationPanelViewController.onControlCenterBlurChanged");

            Class<?> center = XposedHelpers.findClass(
                    "com.flyme.systemui.controlcenter.phone.CenterController", cl);
            XC_MethodHook pin = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sLockPullOn || (classicLockPullArmed())) {
                        param.args[0] = Float.valueOf(1f);
                    }
                }
            };
            XposedBridge.hookAllMethods(center, "setAnimationAlpha", pin);
            XposedBridge.hookAllMethods(center, "setAnimationScale", pin);
            XposedBridge.hookAllMethods(center, "setCollapseAnimationTranslationY", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sLockPullOn || (classicLockPullArmed())) {
                        param.args[0] = Float.valueOf(0f);
                    }
                }
            });
            log("12b hook CenterController setAnimationAlpha/Scale/CollapseTranslation");

            // The panel is made visible by the same call that expands it, one frame before this
            // module's poller would clip it; clipping it in the same call keeps a whole unclipped
            // frame off the screen (the "control centre flashes once when the pull starts" report).
            XposedBridge.hookAllMethods(center, "setExpandedHeightInternal", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!keyguardShowing() || !lockPortrait()) return;
                    // The vendor's close drag is not 1:1.  TouchHandler#onTouch maps a drag that
                    // starts with the panel expanded through
                    //   h' = max(0, (10 * dy + maxPanelHeight) * 0.1)
                    // so the panel snaps to 10% the moment tracking starts (and every upward move
                    // past 10% is clamped to 0) -- a slow upward drag closed it after ~40 px instead
                    // of playing the pull-down backwards.  When the call comes from a live touch we
                    // know the finger's y (recorded in sTouchY by the TouchHandler hook), so the
                    // height is rebuilt as "height it had when the drag started + dy", which makes
                    // the close the exact reverse of the open.
                    try {
                        float y = sTouchY;
                        if (y < 0f) return;                      // not from a touch (animator/fling)
                        Object c = param.thisObject;
                        if (!XposedHelpers.getBooleanField(c, "mTracking")) return;
                        float init = XposedHelpers.getFloatField(c, "mInitialOffsetOnTouch");
                        if (init <= 0.5f) return;                // drag started collapsed: already 1:1
                        float startY = XposedHelpers.getFloatField(c, "mInitialTouchY");
                        float maxPanel = ((Number) XposedHelpers.callMethod(c, "getMaxPanelHeight"))
                                .floatValue();
                        float target = init + (y - startY);
                        if (target < 0f) target = 0f;
                        if (target > maxPanel) target = maxPanel;
                        param.args[0] = Float.valueOf(target);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!keyguardShowing() || !lockPortrait()) return;
                    try {
                        applyCcReveal();
                    } catch (Throwable ignored) {
                    }
                    if (sPanelController != null) {
                        try {
                            XposedHelpers.callMethod(sPanelController, "onControlCenterBlurChanged",
                                    Integer.valueOf(0));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            log("12e hook CenterController setExpandedHeightInternal (synchronous clip)");

            Class<?> qsAnimator = XposedHelpers.findClass(
                    "com.flyme.systemui.controlcenter.qs.MzQsAnimator", cl);
            XposedBridge.hookAllMethods(qsAnimator, "setQSTranslationY", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sLockPullOn || (classicLockPullArmed())) {
                        param.args[0] = Float.valueOf(0f);
                    }
                }
            });
            log("12c hook MzQsAnimator.setQSTranslationY");

            // Record the finger's y while the CC's TouchHandler runs, so the height rewrite above
            // can turn the vendor's compressed close into a 1:1 one.
            try {
                Class<?> touch = XposedHelpers.findClass(
                        "com.flyme.systemui.controlcenter.phone.CenterController$TouchHandler", cl);
                XposedBridge.hookAllMethods(touch, "onTouch", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            android.view.MotionEvent ev = (android.view.MotionEvent) param.args[1];
                            sTouchY = ev.getActionMasked() == android.view.MotionEvent.ACTION_MOVE
                                    ? ev.getY() : -1f;
                        } catch (Throwable t) {
                            sTouchY = -1f;
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sTouchY = -1f;
                    }
                });
                log("12f hook CenterController$TouchHandler.onTouch");
            } catch (Throwable t) {
                log("12f TouchHandler hook failed: " + t);
            }

            // The collapse fling starts from 10% of the panel height, not from where the finger let
            // go (CenterController#createHeightAnimator clamps its start to maxPanelHeight * 0.1), so
            // a quick flick up snapped the whole control centre and the notification column up by the
            // remaining ~1100 px in a single frame before the 350/500 ms animation even started --
            // "上移得太快".  Replace that animator with one that starts where the drag ended; the
            // caller still applies its own duration/interpolator, which is the old design's speed cap.
            XposedBridge.hookAllMethods(center, "createHeightAnimator", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!keyguardShowing() || !lockPortrait()) return;
                    try {
                        Object c = param.thisObject;
                        float target = ((Number) param.args[0]).floatValue();
                        float cur = ((Number) XposedHelpers.getObjectField(c, "mExpandedHeight"))
                                .floatValue();
                        if (target >= cur - 0.5f) return;   // expanding / no collapse: leave it alone
                        final Object cc = c;
                        android.animation.ValueAnimator a = new CollapseAnimator();
                        a.setFloatValues(cur, target);
                        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                            @Override
                            public void onAnimationUpdate(android.animation.ValueAnimator va) {
                                try {
                                    Object v = va.getAnimatedValue();
                                    if (v instanceof Number) {
                                        XposedHelpers.callMethod(cc, "setExpandedHeightInternal",
                                                Float.valueOf(((Number) v).floatValue()));
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                        param.setResult(a);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("12g hook CenterController.createHeightAnimator (collapse from the finger's height)");

            Class<?> scrimView = XposedHelpers.findClass("com.android.systemui.scrim.ScrimView", cl);
            XposedBridge.hookAllMethods(scrimView, "setViewAlpha", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject == sCcBehind
                            && (sLockPullOn || (classicLockPullArmed()))) {
                        param.args[0] = Float.valueOf(0f);
                    }
                }
            });
            log("12d hook ScrimView.setViewAlpha (CC plate)");
        } catch (Throwable t) {
            log("12 classic lock pull hooks failed: " + t);
        }
    }

    /**
     * The collapse animator built by the hook above: a plain ValueAnimator from the height the finger
     * left the panel at, whose duration is capped at {@link #LOCK_CC_COLLAPSE_MS} no matter what the
     * vendor's fling asks for (350 ms, or 500 ms with "control centre full animation" on).
     */
    private static final class CollapseAnimator extends android.animation.ValueAnimator {
        @Override
        public android.animation.ValueAnimator setDuration(long duration) {
            return super.setDuration(Math.min(duration, LOCK_CC_COLLAPSE_MS));
        }
    }

    private static boolean keyguardShowing() {
        ClassLoader cl = sClassLoader;
        if (cl == null) return false;
        try {
            Object inst = sKeyguardState;
            if (inst == null) {
                Class<?> dep = XposedHelpers.findClass("com.android.systemui.Dependency", cl);
                Class<?> ksc = XposedHelpers.findClass(
                        "com.android.systemui.statusbar.policy.KeyguardStateController", cl);
                inst = XposedHelpers.callStaticMethod(dep, "get", ksc);
                sKeyguardState = inst;
            }
            Object v = XposedHelpers.callMethod(inst, "isShowing");
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            if (!sKgLogged) {
                sKgLogged = true;
                log("keyguard state unavailable: " + t);
            }
            return false;
        }
    }

    /* ---------------- 32dp shift of the page below the status bar ---------------- */

    /*
     * The shift itself lives in resources ({@link #putPanelShiftDimens}) and in the header row
     * ({@link #shiftHeaderRow(View)}); there is no runtime positioning hook to keep in sync.
     *
     * A first attempt translated the notification list view instead.  It moved the cards, and the
     * list's clipping rectangle with them, but it left the panel's own maths on the old numbers: the
     * list's rest position is not a scroll limit on this build, it is
     * {@code MzQSImpl.getQsMinCollapsedExpansionHeight() == mMzQSContainer.getY()}, and the container
     * top is exactly what {@code mz_classical_panel_top_padding} sets.  Shifting that dimen moves the
     * list, its clip and the control centre region against the same frame of reference, so no state
     * (expanded, mid-drag, swiped up) can drift out of step.
     */

    /* ------------------------ slider wrapper -------------------------- */

    /**
     * Keep the control-centre column level with the notification column.
     *
     * <p>Landscape used to push the QS scroll view down 25dp because the container top
     * ({@code mz_classical_panel_top_padding}, stock 72dp) sat above the notification column's 97dp
     * line.  The landscape dimen override now puts the container itself on that 97dp line, so the
     * extra padding would push the control centre 25dp below the notifications; it is cleared here.
     * Portrait never had it.
     */
    private static void alignQsColumn(View panel) {
        // the scroll view is an *ancestor* of the panel, not a descendant
        View root = panel;
        for (int i = 0; i < 8 && root.getParent() instanceof View; i++) {
            root = (View) root.getParent();
        }
        View scroll = root.findViewById(idQsScrollView);
        if (scroll == null) return;
        if (scroll.getPaddingTop() != 0) {
            scroll.setPadding(scroll.getPaddingLeft(), 0,
                    scroll.getPaddingRight(), scroll.getPaddingBottom());
        }
    }

    private static void styleSliderWrapper(View panel) {
        if (!(panel instanceof ViewGroup)) return;
        View sliderWrapper = panel.findViewById(idSliderWrapper);
        if (sliderWrapper == null) {
            log("6 slider_wrapper not found (0x" + Integer.toHexString(idSliderWrapper) + ")");
            return;
        }
        float radius = qsCardRadiusPx(panel);
        // The connectivity card is the other half of the same row and carries the same surface: one
        // call site so the two can never drift apart.  Both are painted with CARD_BG_COLOR,
        // i.e. the colour the notification rows use, instead of the opaque white they had -- see
        // applyQsCardFill.
        //
        // Both are also REGISTERED rather than remembered in a field: this runs once per panel, and
        // there are two panels on this build (the classic shade's and the independent control centre
        // the lock screen's pull-down expands), each with its own pair of cards.  Keeping only the
        // last pair meant a day/night switch repainted one panel and left the other on the surface it
        // was born with -- the white cards in dark mode this fixes (see sQsCards).
        View connectivity = panel.findViewById(idConnectivityTilesWrapper);
        registerQsCard(sliderWrapper, radius);
        registerQsCard(connectivity, radius);
        log("6 qs cards applied 0x"
                + Integer.toHexString(sNightUi ? cardColor() : notificationFill())
                + " r=" + radius + " (" + qsCardCount() + " cards registered)");
    }

    /** Register one control-centre card, and the radius it is drawn with, for the theme repaint. */
    private static void registerQsCard(View card, float radius) {
        if (card == null) return;
        sQsCardRadius.put(card, Float.valueOf(radius));
        synchronized (sQsCards) {
            for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = sQsCards.iterator();
                    it.hasNext(); ) {
                View known = it.next().get();
                if (known == null) {
                    it.remove();
                } else if (known == card) {
                    return;                 // the vendor re-inflated / re-attached the same view
                }
            }
            sQsCards.add(new java.lang.ref.WeakReference<>(card));
        }
        applyQsCardFill(card, radius, true);
    }

    /** Live cards in {@link #sQsCards} (drops the collected refs; used by the log line and the poll). */
    private static int qsCardCount() {
        int n = 0;
        synchronized (sQsCards) {
            for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = sQsCards.iterator();
                    it.hasNext(); ) {
                if (it.next().get() == null) it.remove(); else n++;
            }
        }
        return n;
    }

    /** The corner radius the control centre's two cards are drawn with ({@code qs_corner_radius}). */
    private static float qsCardRadiusPx(View panel) {
        float radius = 18f * panel.getResources().getDisplayMetrics().density;
        try {
            radius = panel.getResources().getDimensionPixelSize(idQsCornerRadius);
        } catch (Throwable ignored) {
        }
        return radius;
    }

    /**
     * Repaint every control-centre card for the current theme.
     *
     * <p>Called from the frame poller, because the fill the cards carry is not a resource the vendor
     * re-reads: a panel inflated while the device was light would keep the white card after a switch
     * to dark (measured on the lock screen's independent control centre: two {@code #ddffffff} cards
     * carrying the vendor's white night text -- 独立面板 深色模式 通信/亮度与音量 卡片刷色), and a
     * rotation re-inflates both cards from the stock layout, which puts the vendor's own background
     * back.  {@link #applyQsCardFill} is value-guarded, so the steady state costs one map lookup per
     * card per frame and nothing is re-set.
     *
     * <p>Every registered card is visited, not just the one belonging to the panel that happened to
     * inflate last: the classic shade and the lock screen's independent control centre are separate
     * hierarchies and their cards are repainted by their own identity (see {@link #sQsCards}).
     */
    private static void repaintQsCards(boolean classic) {
        synchronized (sQsCards) {
            for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = sQsCards.iterator();
                    it.hasNext(); ) {
                View card = it.next().get();
                if (card == null) {
                    it.remove();
                    continue;
                }
                Float radius = sQsCardRadius.get(card);
                applyQsCardFill(card, radius == null
                        ? 18f * card.getResources().getDisplayMetrics().density
                        : radius.floatValue(), classic);
            }
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }

    /* ------------------- media row + tile grid (rotation) ------------------- */

    /**
     * Keep the 49px band between the connectivity/sliders card and the tile grid open across a
     * landscape round-trip.
     *
     * <p>The control-centre column is, top to bottom: the card (686px), a 49px band, the tile grid,
     * the media row, the misc tile row.  On this build the band is the <em>top margin</em> of
     * {@code media_misc_wrapper}, and {@code MzQSPanel#changeForLandScape} (called from
     * {@code updateResources}) hides the media wrapper on the way into landscape while nothing
     * restores it on the way back; a {@code GONE} view's margin is not measured, so the card, the
     * tile grid and every row below it then sit 49px too high and the first tile row ends up under
     * the notification column's rounded clipping edge.
     *
     * <p>Measured on the device (1368x3192, density 3.5): first tile row at 1421px from a fresh
     * process and 1372px for the same panel after one landscape round-trip.  The panel chain carried
     * the same 49px: {@code misc_tile_wrapper} 735px -&gt; 686px, {@code misc_tile_layout}
     * 784px -&gt; 735px, {@code tile_page} top 784 -&gt; 735.  49px is 14dp, i.e. exactly
     * {@code notification_corner_radius}.
     *
     * <p>The band has to be restored during the panel's own update pass, before its next layout: a
     * change made only after layout (measured: a spacer view, and the layout's own padding) is not
     * honoured by the vendor's re-inflate on rotation.  Mirroring the media wrapper's visibility onto
     * the row that carries the margin is the variant that held on the device, so that is what this
     * does.
     */
    private static void keepTileGridGap(View panel) {
        if (!(panel instanceof ViewGroup)) return;
        View wrapper = panel.findViewById(idMediaMiscWrapper);  // id/media_misc_wrapper
        if (wrapper == null) return;
        // The 49px band IS this row's topMargin, and a GONE row's margin is not measured.  Forcing the
        // row itself visible (the media views inside it are hidden and measure to 0) puts the band
        // back, and it is done here -- during the panel's own update pass -- because a change made
        // only after layout is not picked up.
        if (wrapper.getVisibility() != View.VISIBLE) wrapper.setVisibility(View.VISIBLE);
        // The row the vendor shows in media-landscape would hold the band open a second time.
        View misc = panel.findViewById(idMiscWrapper);
        if (misc != null && misc.getVisibility() != View.GONE) misc.setVisibility(View.GONE);
    }

}



