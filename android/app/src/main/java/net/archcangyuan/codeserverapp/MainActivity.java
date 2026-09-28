package net.archcangyuan.codeserverapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class MainActivity extends Activity {
    private static final String PREFERENCES = "code_server_app";
    private static final String ADDRESS_KEY = "server_address";
    private static final String PROJECTS_KEY = "saved_projects";
    private static final String KEEP_ALIVE_KEY = "keep_alive_enabled";
    private static final String MOUSE_MODE_KEY = "mouse_mode_enabled";
    private static final String FULLSCREEN_KEY = "fullscreen_enabled";
    private static final String KEYBOARD_LOCK_KEY = "keyboard_lock";
    private static final int KEYBOARD_UNLOCKED = 0;
    private static final int KEYBOARD_LOCKED_OPEN = 1;
    private static final int KEYBOARD_LOCKED_HIDDEN = 2;
    private static final long KEYBOARD_RESHOW_DELAY_MS = 120L;
    private static final long KEYBOARD_HOLD_TIMEOUT_MS = 1_500L;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2001;
    private static final int OVERLAY_PERMISSION_REQUEST = 2002;
    private static final int UPLOAD_FILES_REQUEST = 2003;
    private static final long SESSION_KEEP_ALIVE_PULSE_MS = 10_000L;
    private static final String LEGACY_NATIVE_ZOOM_PERCENT_KEY = "zoom_percent";
    private static final String LAYOUT_ZOOM_STEPS_KEY = "layout_zoom_steps";
    private static final String VIEWPORT_RELOAD_ZOOM_MIGRATED_KEY =
        "viewport_reload_zoom_migrated";
    private static final int DESKTOP_VIEWPORT_WIDTH = 1280;
    private static final int MIN_LAYOUT_ZOOM_STEPS = -10;
    private static final int MAX_LAYOUT_ZOOM_STEPS = 16;
    private static final double LAYOUT_ZOOM_FACTOR = 1.1;
    private static final long PROJECT_SESSION_TTL_MS = 30L * 60L * 1_000L;
    private static final int MAX_HOT_PROJECT_SESSIONS = 10;
    private static final long ADDRESS_BAR_AUTO_HIDE_MS = 5_000L;
    private static final int ACCENT = Color.rgb(103, 80, 164);
    private static final int KEY_BACKGROUND = Color.rgb(230, 230, 234);
    /** Height of the key bar's keys; the bar adds 3 dp above and below. */
    private static final int KEY_HEIGHT_DP = 32;
    /** Height of the address bar, without the top cutout inset. */
    private static final int ADDRESS_BAR_HEIGHT_DP = 44;
    private static final String DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String KEEP_ALIVE_PULSE_SCRIPT =
        "(() => { window.dispatchEvent(new Event('__your_workspace_keep_alive')); "
            + "return document.visibilityState; })()";

    private static final String KEYBOARD_BRIDGE = """
        (() => {
          const PROXY_ID = '__code_server_app_keyboard_proxy';
          // fitWidth is the native view width in points. When given, the page scale is
          // pinned to fit the new layout width for a moment, because web views keep
          // their old scale on live viewport changes and would crop the page. If the
          // page still does not fit, allowReload lets it fall back to one reload.
          const setViewportWidth = (requestedWidth, fitWidth = 0, allowReload = false) => {
            const numericWidth = Number(requestedWidth) || 1280;
            const width = Math.max(200, Math.min(4000, Math.round(numericWidth)));
            // The built-in remote desktop page keeps its viewport at scale 1 and
            // turns the zoom into the remote desktop's pixel density itself.
            if (window.__yourWorkspaceRdpPage) {
              window.__codeServerAppViewportWidth = width;
              window.dispatchEvent(new Event('yourworkspace-rdp-zoom'));
              return width;
            }
            let viewport = document.querySelector('meta[name="viewport"]');
            if (!viewport) {
              viewport = document.createElement('meta');
              viewport.setAttribute('name', 'viewport');
              (document.head || document.documentElement).appendChild(viewport);
            }
            const relaxedContent =
              `width=${width}, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes`;
            const token = (window.__codeServerAppViewportToken || 0) + 1;
            window.__codeServerAppViewportToken = token;
            // On a web RDP page (IronRDP) a reload always reconnects the remote session,
            // which asks for the credentials again, so there is never a fallback reload.
            // Width changes still resize the remote desktop live, but are spaced at least
            // 1.5 s apart (only the latest one is applied) so the session is not asked to
            // resize repeatedly in quick succession.
            if (Number(fitWidth) > 0 && window.__codeServerAppIsRdpPage?.()) {
              allowReload = false;
              const now = Date.now();
              const nextAllowed = (window.__codeServerAppRdpResizeAt || 0) + 1500;
              window.clearTimeout(window.__codeServerAppRdpResizeTimer);
              if (now < nextAllowed) {
                window.__codeServerAppRdpResizeTimer = window.setTimeout(
                  () => setViewportWidth(requestedWidth, fitWidth, false),
                  nextAllowed - now
                );
                return Number(window.__codeServerAppViewportWidth) || width;
              }
              window.__codeServerAppRdpResizeAt = now;
            }
            if (Number(fitWidth) > 0) {
              const scale = Math.max(0.1, Math.min(5, Number(fitWidth) / width)).toFixed(4);
              viewport.setAttribute(
                'content',
                `width=${width}, initial-scale=${scale}, minimum-scale=${scale}, `
                  + `maximum-scale=${scale}, user-scalable=yes`
              );
              window.setTimeout(() => {
                if (window.__codeServerAppViewportToken !== token) return;
                viewport.setAttribute('content', relaxedContent);
                window.dispatchEvent(new Event('resize'));
                if (!allowReload) return;
                window.setTimeout(() => {
                  if (window.__codeServerAppViewportToken !== token) return;
                  // innerWidth follows the visual viewport in Chromium, so compare the
                  // visible width against the layout width instead.
                  const layout = document.documentElement.clientWidth || width;
                  const visible = window.visualViewport ? window.visualViewport.width : layout;
                  if (Math.abs(visible - layout) <= layout * 0.03) return;
                  const reloadKey = '__codeServerAppViewportReloadAt';
                  try {
                    const lastReload = Number(window.sessionStorage.getItem(reloadKey)) || 0;
                    if (Date.now() - lastReload < 15000) return;
                    window.sessionStorage.setItem(reloadKey, String(Date.now()));
                  } catch (_) {
                    return;
                  }
                  window.location.reload();
                }, 250);
              }, 180);
            } else {
              viewport.setAttribute('content', relaxedContent);
            }
            document.documentElement.style.zoom = '1';
            if (document.body) {
              document.body.style.zoom = '1';
              document.body.style.width = '';
              document.body.style.minWidth = '';
            }
            window.__codeServerAppViewportWidth = width;
            requestAnimationFrame(() => window.dispatchEvent(new Event('resize')));
            return width;
          };

          window.__codeServerAppSetViewportWidth = setViewportWidth;
          setViewportWidth(window.__codeServerAppViewportWidth || 1280);

          const findIronRdpCanvas = () => {
            const roots = [document];
            const visited = new Set();
            for (let index = 0; index < roots.length && index < 128; index += 1) {
              const root = roots[index];
              if (!root || visited.has(root) || !root.querySelectorAll) continue;
              visited.add(root);

              const directCanvas = root.querySelector('canvas#renderer');
              if (directCanvas) return directCanvas;

              const ironHosts = root.querySelectorAll(
                'iron-remote-desktop, iron-remote-gui'
              );
              for (const host of ironHosts) {
                const canvas = host.shadowRoot?.querySelector('canvas#renderer');
                if (canvas) return canvas;
              }

              for (const element of root.querySelectorAll('*')) {
                if (element.shadowRoot && !visited.has(element.shadowRoot)) {
                  roots.push(element.shadowRoot);
                }
                if (element.tagName === 'IFRAME') {
                  try {
                    if (element.contentDocument) roots.push(element.contentDocument);
                  } catch (_) {}
                }
              }
            }
            return null;
          };

          window.__codeServerAppIsRdpPage = () => Boolean(findIronRdpCanvas());

          const existingBridge = window.__codeServerAppKeyboard;
          if (existingBridge && existingBridge.version >= 17) {
            window.__codeServerAppForceKeyboard = () => existingBridge.forceKeyboard();
            existingBridge.installRdpGestures?.();
            existingBridge.installDesktopGestures?.();
            return;
          }

          const state = {
            control: false,
            shift: false,
            target: null,
            composing: false,
            lastForwardedKey: '',
            lastForwardedAt: 0,
            lastCompositionText: '',
            lastCompositionAt: 0,
            ironRdpCanvas: null,
            gestureCanvas: null,
            desktopGestureInstalled: false
          };

          const pointFromTouch = (touch) => ({
            clientX: touch.clientX,
            clientY: touch.clientY,
            screenX: touch.screenX,
            screenY: touch.screenY
          });

          const dispatchMouse = (canvas, type, point, button, buttons) => {
            const eventWindow = canvas.ownerDocument?.defaultView || window;
            canvas.dispatchEvent(new eventWindow.MouseEvent(type, {
              bubbles: true,
              cancelable: true,
              composed: true,
              view: eventWindow,
              clientX: point.clientX,
              clientY: point.clientY,
              screenX: point.screenX,
              screenY: point.screenY,
              button,
              buttons
            }));
          };

          // IronRDP synchronizes the remote Caps Lock / Num Lock state from every
          // mouseenter on its canvas. Touch-derived events can carry a stale lock state
          // and turn Caps Lock on in the remote session, so real mouseenter events are
          // replaced by one with Caps Lock off and Num Lock on. mouseenter reaches only
          // the entered element, so this listens (capturing, ahead of IronRDP) on the
          // canvas and its ancestors inside the shadow root. Pressing an actual lock key
          // still synchronizes through the keyboard path.
          const replaceRdpLockState = (event) => {
            if (!event.isTrusted) return;
            const target = event.currentTarget;
            event.stopImmediatePropagation();
            const eventWindow = target.ownerDocument?.defaultView || window;
            target.dispatchEvent(new eventWindow.MouseEvent('mouseenter', {
              bubbles: false,
              cancelable: false,
              composed: true,
              view: eventWindow,
              clientX: event.clientX,
              clientY: event.clientY,
              screenX: event.screenX,
              screenY: event.screenY,
              buttons: event.buttons,
              modifierCapsLock: false,
              modifierNumLock: true
            }));
          };

          const installRdpGestures = () => {
            const canvas = findIronRdpCanvas();
            if (!canvas) return false;
            state.ironRdpCanvas = canvas;
            if (state.gestureCanvas === canvas) return true;
            state.gestureCanvas = canvas;
            for (let element = canvas; element && element.nodeType === 1; element = element.parentNode) {
              element.addEventListener('mouseenter', replaceRdpLockState, true);
            }

            // Preserve WebView panning and IronRDP's native coordinate mapping.
            canvas.style.touchAction = '';
            canvas.style.webkitTouchCallout = 'none';

            let gesture = null;

            const releaseGesture = () => {
              if (gesture?.timer) window.clearTimeout(gesture.timer);
            };

            const sendButton = (point, button) => {
              const downButtons = button === 2 ? 2 : 1;
              dispatchMouse(canvas, 'mousemove', point, 0, 0);
              dispatchMouse(canvas, 'mousedown', point, button, downButtons);
              dispatchMouse(canvas, 'mouseup', point, button, 0);
            };

            canvas.addEventListener('contextmenu', (event) => {
              if (!gesture) return;
              event.preventDefault();
              event.stopImmediatePropagation();
            }, true);

            // Two-finger swipes scroll like a mouse wheel at the fingers' midpoint.
            // Pixel deltas, doubled so a swipe covers a comfortable distance.
            const WHEEL_GAIN = 2;
            let wheel = null;
            const midpoint = (touches) => {
              const first = pointFromTouch(touches[0]);
              const second = pointFromTouch(touches[1]);
              return {
                clientX: (first.clientX + second.clientX) / 2,
                clientY: (first.clientY + second.clientY) / 2,
                screenX: (first.screenX + second.screenX) / 2,
                screenY: (first.screenY + second.screenY) / 2
              };
            };
            const dispatchWheel = (point, deltaX, deltaY) => {
              const eventWindow = canvas.ownerDocument?.defaultView || window;
              canvas.dispatchEvent(new eventWindow.WheelEvent('wheel', {
                bubbles: true,
                cancelable: true,
                composed: true,
                view: eventWindow,
                clientX: point.clientX,
                clientY: point.clientY,
                screenX: point.screenX,
                screenY: point.screenY,
                deltaX,
                deltaY,
                deltaMode: 0
              }));
            };

            canvas.addEventListener('touchstart', (event) => {
              if (event.touches.length === 2 && !gesture?.dragging) {
                releaseGesture();
                gesture = null;
                wheel = { last: midpoint(event.touches) };
                dispatchMouse(canvas, 'mousemove', wheel.last, 0, 0);
                return;
              }
              if (event.touches.length !== 1) return;
              const start = pointFromTouch(event.touches[0]);
              gesture = {
                start,
                last: start,
                armed: false,
                dragging: false,
                cancelled: false,
                timer: window.setTimeout(() => {
                  if (gesture && !gesture.cancelled) gesture.armed = true;
                }, 550)
              };
            }, { capture: true, passive: true });

            canvas.addEventListener('touchmove', (event) => {
              if (wheel && event.touches.length === 2) {
                event.preventDefault();
                event.stopImmediatePropagation();
                const point = midpoint(event.touches);
                const dx = point.clientX - wheel.last.clientX;
                const dy = point.clientY - wheel.last.clientY;
                if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return;
                wheel.last = point;
                // IronRDP scrolls one axis per event: send the dominant one.
                if (Math.abs(dy) >= Math.abs(dx)) {
                  dispatchWheel(point, 0, -dy * WHEEL_GAIN);
                } else {
                  dispatchWheel(point, -dx * WHEEL_GAIN, 0);
                }
                return;
              }
              if (!gesture || event.touches.length !== 1) return;
              const point = pointFromTouch(event.touches[0]);
              gesture.last = point;
              const distance = Math.hypot(
                point.clientX - gesture.start.clientX,
                point.clientY - gesture.start.clientY
              );

              if (!gesture.armed) {
                if (distance >= 7) {
                  releaseGesture();
                  gesture.cancelled = true;
                }
                return;
              }

              if (!gesture.dragging && distance < 7) return;

              event.preventDefault();
              event.stopImmediatePropagation();
              if (!gesture.dragging) {
                gesture.dragging = true;
                dispatchMouse(canvas, 'mousemove', gesture.start, 0, 0);
                dispatchMouse(canvas, 'mousedown', gesture.start, 0, 1);
              }
              dispatchMouse(canvas, 'mousemove', point, 0, 1);
            }, { capture: true, passive: false });

            const finishGesture = (event, cancelled) => {
              if (wheel && event.touches.length < 2) wheel = null;
              if (!gesture) return;
              releaseGesture();
              const touch = event.changedTouches?.[0];
              const point = touch ? pointFromTouch(touch) : gesture.last;

              if (gesture.dragging) {
                event.preventDefault();
                event.stopImmediatePropagation();
                dispatchMouse(canvas, 'mousemove', point, 0, 1);
                dispatchMouse(canvas, 'mouseup', point, 0, 0);
              } else if (gesture.armed && !gesture.cancelled) {
                event.preventDefault();
                event.stopImmediatePropagation();
                sendButton(point, 2);
              } else if (cancelled) {
                gesture.cancelled = true;
              }
              gesture = null;
            };

            canvas.addEventListener('touchend', (event) => {
              finishGesture(event, false);
            }, { capture: true, passive: false });
            canvas.addEventListener('touchcancel', (event) => {
              finishGesture(event, true);
            }, { capture: true, passive: false });
            return true;
          };

          const installDesktopGestures = () => {
            if (state.desktopGestureInstalled) return true;
            state.desktopGestureInstalled = true;

            let gesture = null;

            const eventPath = (event) => typeof event.composedPath === 'function'
              ? event.composedPath()
              : [event.target];
            const isIronRdpEvent = (event) => {
              const canvas = state.ironRdpCanvas || findIronRdpCanvas();
              return Boolean(canvas && eventPath(event).includes(canvas));
            };
            const isNativeTextTarget = (target) => {
              if (!target || target.nodeType !== 1) return false;
              const tagName = target.tagName;
              return tagName === 'INPUT'
                || tagName === 'TEXTAREA'
                || tagName === 'SELECT'
                || Boolean(target.isContentEditable);
            };
            const targetAt = (point, fallback) => {
              try {
                return document.elementFromPoint(point.clientX, point.clientY)
                  || fallback;
              } catch (_) {
                return fallback;
              }
            };
            const clearTimer = (activeGesture) => {
              if (activeGesture?.timer) window.clearTimeout(activeGesture.timer);
            };
            const sendContextMenu = (target, point) => {
              if (!target?.dispatchEvent) return;
              dispatchMouse(target, 'mousemove', point, 0, 0);
              dispatchMouse(target, 'mousedown', point, 2, 2);
              dispatchMouse(target, 'mouseup', point, 2, 0);
              dispatchMouse(target, 'contextmenu', point, 2, 0);
            };

            document.addEventListener('contextmenu', (event) => {
              if (!event.isTrusted || !gesture || isIronRdpEvent(event)) return;
              event.preventDefault();
              event.stopImmediatePropagation();
            }, true);

            // Two-finger swipes scroll like a mouse wheel (pages and Monaco alike),
            // following the midpoint of the fingers. They replace pinch zoom; the
            // app's zoom slider sets the page zoom.
            let wheel = null;
            const touchMidpoint = (touches) => ({
              clientX: (touches[0].clientX + touches[1].clientX) / 2,
              clientY: (touches[0].clientY + touches[1].clientY) / 2
            });

            document.addEventListener('touchstart', (event) => {
              if (event.touches.length === 2
                  && !isIronRdpEvent(event)
                  && !gesture?.dragging) {
                clearTimer(gesture);
                gesture = null;
                wheel = { last: touchMidpoint(event.touches) };
                return;
              }
              if (event.touches.length !== 1 || isIronRdpEvent(event)) return;
              const path = eventPath(event);
              const startTarget = path.find((target) => target?.dispatchEvent)
                || event.target;
              if (!startTarget || isNativeTextTarget(startTarget)) return;
              if (startTarget.style) startTarget.style.webkitTouchCallout = 'none';
              const start = pointFromTouch(event.touches[0]);
              gesture = {
                start,
                last: start,
                startTarget,
                armed: false,
                dragging: false,
                cancelled: false,
                timer: window.setTimeout(() => {
                  if (gesture && !gesture.cancelled) gesture.armed = true;
                }, 550)
              };
            }, { capture: true, passive: true });

            document.addEventListener('touchmove', (event) => {
              if (wheel && event.touches.length === 2) {
                if (event.cancelable) event.preventDefault();
                event.stopImmediatePropagation();
                const point = touchMidpoint(event.touches);
                const dx = wheel.last.clientX - point.clientX;
                const dy = wheel.last.clientY - point.clientY;
                if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return;
                wheel.last = point;
                mouseWheel(point.clientX, point.clientY, dx, dy);
                return;
              }
              if (!gesture || event.touches.length !== 1) return;
              const point = pointFromTouch(event.touches[0]);
              gesture.last = point;
              const distance = Math.hypot(
                point.clientX - gesture.start.clientX,
                point.clientY - gesture.start.clientY
              );

              if (!gesture.armed) {
                if (distance >= 7) {
                  clearTimer(gesture);
                  gesture.cancelled = true;
                }
                return;
              }
              if (!gesture.dragging && distance < 7) return;

              event.preventDefault();
              event.stopImmediatePropagation();
              if (!gesture.dragging) {
                gesture.dragging = true;
                dispatchMouse(gesture.startTarget, 'mousemove', gesture.start, 0, 0);
                dispatchMouse(gesture.startTarget, 'mousedown', gesture.start, 0, 1);
              }
              const moveTarget = targetAt(point, gesture.startTarget);
              dispatchMouse(moveTarget, 'mousemove', point, 0, 1);
            }, { capture: true, passive: false });

            const finishGesture = (event, cancelled) => {
              if (wheel && event.touches.length < 2) wheel = null;
              if (!gesture) return;
              const activeGesture = gesture;
              gesture = null;
              clearTimer(activeGesture);
              const touch = event.changedTouches?.[0];
              const point = touch ? pointFromTouch(touch) : activeGesture.last;
              const endTarget = targetAt(point, activeGesture.startTarget);

              if (activeGesture.dragging) {
                event.preventDefault();
                event.stopImmediatePropagation();
                dispatchMouse(endTarget, 'mousemove', point, 0, 1);
                dispatchMouse(endTarget, 'mouseup', point, 0, 0);
              } else if (activeGesture.armed
                  && !activeGesture.cancelled
                  && !cancelled) {
                event.preventDefault();
                event.stopImmediatePropagation();
                sendContextMenu(endTarget, point);
              }
            };

            document.addEventListener('touchend', (event) => {
              finishGesture(event, false);
            }, { capture: true, passive: false });
            document.addEventListener('touchcancel', (event) => {
              finishGesture(event, true);
            }, { capture: true, passive: false });
            return true;
          };

          installRdpGestures();
          installDesktopGestures();
          window.setInterval(installRdpGestures, 1000);

          // Cloudflare's browser RDP keeps a short-lived token from page load. When the
          // session reconnects after it expired, the page shows `"exp" claim timestamp
          // check failed` although the Access login is still valid. A reload fetches a
          // fresh token (only the Windows password is asked again), so reload
          // automatically, at most once every 30 s. The check runs only on RDP pages
          // and small error pages, never on large pages such as the workbench.
          const expiredAccessTokenPattern = /claim timestamp check failed/i;
          let webRdpPageSeen = false;
          const collectPageText = () => {
            const roots = [document];
            let text = '';
            for (let index = 0; index < roots.length && index < 64; index += 1) {
              const root = roots[index];
              text += ` ${(root.body || root).textContent || ''}`;
              if (text.length > 200000 || !root.querySelectorAll) break;
              for (const element of root.querySelectorAll('*')) {
                if (element.shadowRoot) roots.push(element.shadowRoot);
                if (element.tagName === 'IFRAME') {
                  try {
                    if (element.contentDocument) roots.push(element.contentDocument);
                  } catch (_) {}
                }
              }
            }
            return text;
          };
          const reloadOnExpiredAccessToken = () => {
            if (state.ironRdpCanvas) webRdpPageSeen = true;
            if (!webRdpPageSeen && document.getElementsByTagName('*').length > 400) return;
            if (!expiredAccessTokenPattern.test(collectPageText())) return;
            const reloadKey = '__codeServerAppAccessTokenReloadAt';
            try {
              const lastReload = Number(window.sessionStorage.getItem(reloadKey)) || 0;
              if (Date.now() - lastReload < 30000) return;
              window.sessionStorage.setItem(reloadKey, String(Date.now()));
            } catch (_) {
              return;
            }
            window.location.reload();
          };
          window.setInterval(reloadOnExpiredAccessToken, 2000);
          const isProxy = (element) => Boolean(element && element.id === PROXY_ID);

          const deepestActiveElement = (rootDocument) => {
            let active = rootDocument.activeElement;
            for (let depth = 0; active && depth < 6; depth += 1) {
              if (active.tagName !== 'IFRAME') break;
              try {
                const childDocument = active.contentDocument;
                if (!childDocument || !childDocument.activeElement) break;
                active = childDocument.activeElement;
              } catch (_) {
                break;
              }
            }
            return active;
          };

          const rememberTarget = (candidate) => {
            if (!candidate || isProxy(candidate)) return;
            const ownerDocument = candidate.ownerDocument;
            const isGeneric = candidate.tagName === 'HTML'
              || Boolean(ownerDocument && candidate === ownerDocument.body);
            if (!isGeneric || !state.target) state.target = candidate;
          };

          // IronRDP only takes keys while its canvas has focus; a tap elsewhere
          // (e.g. a file bar button) moves it away, so restore it before typing.
          const focusedIronRdpCanvas = (canvas) => {
            const host = canvas.getRootNode?.()?.host;
            if (host && document.activeElement !== host) {
              canvas.focus({ preventScroll: true });
            }
            return canvas;
          };

          const activeTarget = () => {
            if (state.ironRdpCanvas && state.ironRdpCanvas.isConnected) {
              return focusedIronRdpCanvas(state.ironRdpCanvas);
            }
            // On the built-in remote desktop page all typing belongs to the
            // remote desktop, even before KB was pressed.
            if (window.__yourWorkspaceRdpPage) {
              const canvas = findIronRdpCanvas();
              if (canvas) {
                state.ironRdpCanvas = canvas;
                return focusedIronRdpCanvas(canvas);
              }
            }
            const current = deepestActiveElement(document);
            if (current && !isProxy(current)) {
              rememberTarget(current);
              const ownerDocument = current.ownerDocument;
              const isGeneric = current.tagName === 'HTML'
                || Boolean(ownerDocument && current === ownerDocument.body);
              if (!isGeneric) return current;
            }
            if (state.target && state.target.isConnected) return state.target;
            const fallback = document.querySelector(
              'canvas, [role="application"], [tabindex="0"]'
            ) || document.body || document.documentElement;
            rememberTarget(fallback);
            return fallback;
          };

          const defineLegacyKeyCodes = (event, keyCode, charCode = 0) => {
            try {
              Object.defineProperty(event, 'keyCode', { get: () => keyCode });
              Object.defineProperty(event, 'which', {
                get: () => charCode || keyCode
              });
              Object.defineProperty(event, 'charCode', { get: () => charCode });
            } catch (_) {}
          };

          const dispatchKeyPhase = (
            target,
            type,
            key,
            code,
            keyCode,
            source = null
          ) => {
            if (!target) return false;
            const printable = typeof key === 'string' && Array.from(key).length === 1;
            const event = new KeyboardEvent(type, {
              key,
              code: code || '',
              location: source ? source.location : 0,
              repeat: source ? source.repeat : false,
              ctrlKey: state.control || Boolean(source && source.ctrlKey),
              shiftKey: state.shift || Boolean(source && source.shiftKey),
              altKey: Boolean(source && source.altKey),
              metaKey: Boolean(source && source.metaKey),
              bubbles: true,
              cancelable: true,
              composed: true
            });
            const charCode = type === 'keypress' && printable
              ? key.codePointAt(0)
              : 0;
            defineLegacyKeyCodes(event, keyCode || 0, charCode);
            return target.dispatchEvent(event);
          };

          const keyInfoForText = (key) => {
            if (/^[a-z]$/i.test(key)) {
              const upper = key.toUpperCase();
              return {
                code: `Key${upper}`,
                keyCode: upper.charCodeAt(0),
                shift: key === upper
              };
            }
            if (/^[0-9]$/.test(key)) {
              return {
                code: `Digit${key}`,
                keyCode: key.charCodeAt(0),
                shift: false
              };
            }
            if (key === ' ') return { code: 'Space', keyCode: 32, shift: false };
            const punctuation = {
              '`': ['Backquote', 192, false], '~': ['Backquote', 192, true],
              '-': ['Minus', 189, false], '_': ['Minus', 189, true],
              '=': ['Equal', 187, false], '+': ['Equal', 187, true],
              '[': ['BracketLeft', 219, false], '{': ['BracketLeft', 219, true],
              ']': ['BracketRight', 221, false], '}': ['BracketRight', 221, true],
              '\\\\': ['Backslash', 220, false], '|': ['Backslash', 220, true],
              ';': ['Semicolon', 186, false], ':': ['Semicolon', 186, true],
              "'": ['Quote', 222, false], '"': ['Quote', 222, true],
              ',': ['Comma', 188, false], '<': ['Comma', 188, true],
              '.': ['Period', 190, false], '>': ['Period', 190, true],
              '/': ['Slash', 191, false], '?': ['Slash', 191, true],
              '!': ['Digit1', 49, true], '@': ['Digit2', 50, true],
              '#': ['Digit3', 51, true], '$': ['Digit4', 52, true],
              '%': ['Digit5', 53, true], '^': ['Digit6', 54, true],
              '&': ['Digit7', 55, true], '*': ['Digit8', 56, true],
              '(': ['Digit9', 57, true], ')': ['Digit0', 48, true]
            };
            const mapped = punctuation[key];
            if (mapped) {
              return { code: mapped[0], keyCode: mapped[1], shift: mapped[2] };
            }
            return {
              code: '',
              keyCode: key.codePointAt(0) || 0,
              shift: false
            };
          };

          const dispatchCompleteKey = (key, code, keyCode, forceShift = false) => {
            const target = activeTarget();
            if (!target) return;
            const needsSyntheticShift = forceShift && !state.shift;
            const source = forceShift ? { shiftKey: true } : null;
            if (needsSyntheticShift) {
              dispatchKeyPhase(
                target,
                'keydown',
                'Shift',
                'ShiftLeft',
                16,
                { shiftKey: true, location: 1 }
              );
            }
            dispatchKeyPhase(target, 'keydown', key, code, keyCode, source);
            if (Array.from(key).length === 1 && !state.control) {
              dispatchKeyPhase(target, 'keypress', key, code, keyCode, source);
            }
            dispatchKeyPhase(target, 'keyup', key, code, keyCode, source);
            if (needsSyntheticShift) {
              dispatchKeyPhase(target, 'keyup', 'Shift', 'ShiftLeft', 16);
            }
          };

          const dispatchShortcut = (
            key,
            code,
            keyCode,
            useControl = false,
            useShift = false
          ) => {
            const target = activeTarget();
            if (!target) return false;
            const needsControl = useControl && !state.control;
            const needsShift = useShift && !state.shift;
            if (needsControl) {
              dispatchKeyPhase(
                target,
                'keydown',
                'Control',
                'ControlLeft',
                17,
                { ctrlKey: true, location: 1 }
              );
            }
            if (needsShift) {
              dispatchKeyPhase(
                target,
                'keydown',
                'Shift',
                'ShiftLeft',
                16,
                { ctrlKey: useControl, shiftKey: true, location: 1 }
              );
            }
            const source = {
              ctrlKey: state.control || useControl,
              shiftKey: state.shift || useShift
            };
            dispatchKeyPhase(target, 'keydown', key, code, keyCode, source);
            dispatchKeyPhase(target, 'keyup', key, code, keyCode, source);
            if (needsShift) {
              dispatchKeyPhase(
                target,
                'keyup',
                'Shift',
                'ShiftLeft',
                16,
                { ctrlKey: state.control || useControl }
              );
            }
            if (needsControl) {
              dispatchKeyPhase(target, 'keyup', 'Control', 'ControlLeft', 17);
            }
            return true;
          };

          const forwardText = (text) => {
            for (const key of Array.from(text || '')) {
              const info = keyInfoForText(key);
              // IronRDP sends scancodes and drops characters without a physical
              // key (Chinese, Japanese, ...). The built-in remote desktop page
              // lets its Unicode mode be switched on for just those characters.
              const unicodeMode = !info.code ? window.__rdpKeyboardUnicodeMode : null;
              if (typeof unicodeMode === 'function') unicodeMode(true);
              try {
                dispatchCompleteKey(key, info.code, info.keyCode, info.shift);
              } finally {
                if (typeof unicodeMode === 'function') unicodeMode(false);
              }
            }
          };

          const wasRecentlyForwarded = (key) =>
            state.lastForwardedKey === key
              && performance.now() - state.lastForwardedAt < 500;

          const handleProxyInput = (event) => {
            if (!isProxy(event.target)) return;
            event.stopImmediatePropagation();
            const input = event.target;
            if (event.isComposing || state.composing) return;

            const inputType = event.inputType || '';
            if (inputType.startsWith('deleteContentBackward')) {
              if (!wasRecentlyForwarded('Backspace')) {
                dispatchCompleteKey('Backspace', 'Backspace', 8);
              }
            } else if (inputType === 'insertLineBreak'
                || inputType === 'insertParagraph') {
              if (!wasRecentlyForwarded('Enter')) {
                dispatchCompleteKey('Enter', 'Enter', 13);
              }
            } else {
              const text = event.data != null ? event.data : input.value;
              const duplicateComposition = text
                && text === state.lastCompositionText
                && performance.now() - state.lastCompositionAt < 500;
              const duplicateKey = text
                && Array.from(text).length === 1
                && wasRecentlyForwarded(text);
              if (text && !duplicateComposition && !duplicateKey) forwardText(text);
            }
            input.value = '';
          };

          const ensureProxy = () => {
            let input = document.getElementById(PROXY_ID);
            if (input) return input;
            input = document.createElement('input');
            input.id = PROXY_ID;
            input.type = 'text';
            input.inputMode = 'text';
            input.autocomplete = 'off';
            input.autocapitalize = 'off';
            input.spellcheck = false;
            input.setAttribute('enterkeyhint', 'enter');
            input.setAttribute('aria-label', 'YourWorkspace keyboard proxy');
            Object.assign(input.style, {
              position: 'fixed',
              left: '1px',
              bottom: '1px',
              width: '1px',
              height: '1px',
              padding: '0',
              border: '0',
              opacity: '0.01',
              zIndex: '2147483647'
            });
            input.addEventListener('input', handleProxyInput);
            input.addEventListener('compositionstart', (event) => {
              event.stopImmediatePropagation();
              state.composing = true;
            });
            input.addEventListener('compositionend', (event) => {
              event.stopImmediatePropagation();
              state.composing = false;
              const text = event.data || input.value;
              if (text) {
                forwardText(text);
                state.lastCompositionText = text;
                state.lastCompositionAt = performance.now();
              }
              input.value = '';
            });
            (document.body || document.documentElement).appendChild(input);
            return input;
          };

          const forceKeyboard = () => {
            installRdpGestures();
            const ironRdpCanvas = findIronRdpCanvas();
            if (ironRdpCanvas) {
              state.ironRdpCanvas = ironRdpCanvas;
              rememberTarget(ironRdpCanvas);
              ironRdpCanvas.focus({ preventScroll: true });
              return 'ironrdp';
            }
            rememberTarget(deepestActiveElement(document));
            return activeTarget() ? 'generic' : 'missing';
          };

          const forwardProxyKey = (event) => {
            if (!event.isTrusted || !isProxy(event.target)) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            const keyCode = event.keyCode || event.which || 0;
            if (event.isComposing || keyCode === 229
                || event.key === 'Unidentified' || event.key === 'Process'
                || event.key === 'Dead') {
              return;
            }
            const target = activeTarget();
            dispatchKeyPhase(target, event.type, event.key, event.code, keyCode, event);
            state.lastForwardedKey = event.key;
            state.lastForwardedAt = performance.now();
          };

          const dispatchModifier = (key, code, keyCode, isDown) => {
            dispatchKeyPhase(
              activeTarget(),
              isDown ? 'keydown' : 'keyup',
              key,
              code,
              keyCode
            );
          };

          const redispatchWithLockedModifiers = (event) => {
            if (!event.isTrusted || (!state.control && !state.shift)) return;
            if (event.key === 'Control' || event.key === 'Shift') return;

            const target = event.target || activeTarget();
            if (!target) return;

            event.preventDefault();
            event.stopImmediatePropagation();

            const shiftedKey = state.shift && event.key.length === 1
              ? event.key.toUpperCase()
              : event.key;
            dispatchKeyPhase(
              target,
              event.type,
              shiftedKey,
              event.code,
              event.keyCode || event.which || 0,
              event
            );
          };

          document.addEventListener('pointerdown', (event) => {
            const path = typeof event.composedPath === 'function'
              ? event.composedPath()
              : [];
            rememberTarget(path[0] || event.target);
          }, true);
          document.addEventListener('focusin', (event) => {
            rememberTarget(event.target);
          }, true);
          document.addEventListener('keydown', forwardProxyKey, true);
          document.addEventListener('keyup', forwardProxyKey, true);
          document.addEventListener('keydown', redispatchWithLockedModifiers, true);
          document.addEventListener('keyup', redispatchWithLockedModifiers, true);

          // Real mice use pointerId 1 in Chromium, which would let pages capture a
          // pointer that never delivers the synthetic moves. Use a private id and
          // emulate setPointerCapture for it instead.
          const SYNTHETIC_POINTER_ID = 7331;

          const mouse = {
            buttons: 0,
            captureTarget: null,
            lastX: 0,
            lastY: 0,
            lastHit: null,
            hoverPath: [],
            downTargets: {},
            clickCount: 0,
            lastDownAt: 0,
            lastDownX: 0,
            lastDownY: 0
          };

          // Mouse mode: the page works like a touchpad (finger movement moves the
          // cursor, a tap clicks at the cursor) and in-page L/R buttons click.
          // Everything runs inside real touch handlers, so clicks carry user
          // activation (clipboard writes, window.open) like a physical mouse.
          const mouseMode = {
            enabled: false,
            viewWidth: 0,
            scale: 1,
            host: null,
            cursor: null,
            left: null,
            right: null,
            lockButton: null,
            touches: new Map(),
            cursorX: -1,
            cursorY: -1,
            leftHeld: false,
            leftLocked: false,
            leftLockArmed: false,
            leftMoved: false,
            leftUnlockPending: false,
            lockTimer: 0,
            rightHeld: false,
            // Set by the app's keyboard lock ("locked hidden"): like mouse mode,
            // editable elements get inputmode=none so the keyboard never opens.
            keyboardLocked: false,
            inputModes: new Map()
          };

          const installPointerCapture = (eventWindow) => {
            const prototype = eventWindow?.Element?.prototype;
            if (!prototype || prototype.__codeServerAppPointerCapture) return;
            const nativeSet = prototype.setPointerCapture;
            const nativeRelease = prototype.releasePointerCapture;
            const nativeHas = prototype.hasPointerCapture;
            prototype.setPointerCapture = function (pointerId) {
              if (pointerId !== SYNTHETIC_POINTER_ID) return nativeSet.call(this, pointerId);
              mouse.captureTarget = this;
            };
            prototype.releasePointerCapture = function (pointerId) {
              if (pointerId !== SYNTHETIC_POINTER_ID) return nativeRelease.call(this, pointerId);
              if (mouse.captureTarget === this) mouse.captureTarget = null;
            };
            prototype.hasPointerCapture = function (pointerId) {
              if (pointerId !== SYNTHETIC_POINTER_ID) return nativeHas.call(this, pointerId);
              return mouse.captureTarget === this;
            };
            Object.defineProperty(prototype, '__codeServerAppPointerCapture', { value: true });
          };
          installPointerCapture(window);

          const mouseButtonMask = (button) => {
            if (button === 2) return 2;
            if (button === 1) return 4;
            return 1;
          };

          // Resolve the deepest element under the cursor, descending into open
          // shadow roots (IronRDP) and same-origin iframes.
          const mouseHitTest = (x, y) => {
            let root = document;
            let localX = x;
            let localY = y;
            let hit = null;
            for (let depth = 0; depth < 8; depth += 1) {
              let element = root.elementFromPoint(localX, localY);
              if (element && element === mouseMode.host) {
                element = root.elementsFromPoint(localX, localY)
                  .find((candidate) => candidate !== mouseMode.host) || null;
              }
              for (let level = 0; element?.shadowRoot && level < 16; level += 1) {
                const inner = element.shadowRoot.elementFromPoint(localX, localY);
                if (!inner || inner === element) break;
                element = inner;
              }
              if (!element) break;
              hit = {
                element,
                clientX: localX,
                clientY: localY,
                view: element.ownerDocument?.defaultView || window
              };
              if (element.tagName !== 'IFRAME') break;
              let childDocument = null;
              try {
                childDocument = element.contentDocument;
              } catch (_) {}
              if (!childDocument) break;
              const rect = element.getBoundingClientRect();
              localX -= rect.left + element.clientLeft;
              localY -= rect.top + element.clientTop;
              root = childDocument;
            }
            return hit;
          };

          const composedAncestors = (element) => {
            const chain = [];
            for (let node = element; node && chain.length < 128;) {
              if (node.nodeType === 1) chain.push(node);
              node = node.parentNode || node.host || null;
            }
            return chain;
          };

          const mouseEventInit = (hit, button, buttons, detail, bubbles) => ({
            bubbles,
            cancelable: bubbles,
            composed: true,
            view: hit.view,
            detail,
            clientX: hit.clientX,
            clientY: hit.clientY,
            screenX: hit.clientX,
            screenY: hit.clientY,
            button,
            buttons,
            ctrlKey: state.control,
            shiftKey: state.shift,
            altKey: false,
            metaKey: false,
            // IronRDP copies lock-key state from mouseenter into the remote session.
            modifierCapsLock: false,
            modifierNumLock: true
          });

          const fireMouse = (hit, element, type, button, buttons, detail = 0, bubbles = true) => {
            const eventWindow = element.ownerDocument?.defaultView || hit.view;
            return element.dispatchEvent(new eventWindow.MouseEvent(
              type,
              mouseEventInit(hit, button, buttons, detail, bubbles)
            ));
          };

          const firePointer = (hit, element, type, button, buttons, bubbles = true) => {
            const eventWindow = element.ownerDocument?.defaultView || hit.view;
            if (typeof eventWindow.PointerEvent !== 'function') return true;
            return element.dispatchEvent(new eventWindow.PointerEvent(type, {
              ...mouseEventInit(hit, button, buttons, 0, bubbles),
              pointerId: SYNTHETIC_POINTER_ID,
              pointerType: 'mouse',
              isPrimary: true,
              width: 1,
              height: 1,
              pressure: buttons ? 0.5 : 0
            }));
          };

          const updateMouseHover = (hit) => {
            const previousPath = mouse.hoverPath;
            const nextPath = composedAncestors(hit.element);
            const previous = previousPath[0] || null;
            const next = nextPath[0] || null;
            if (previous === next) return;
            const leaveHit = mouse.lastHit || hit;
            if (previous && previous.isConnected) {
              firePointer(leaveHit, previous, 'pointerout', 0, mouse.buttons);
              fireMouse(leaveHit, previous, 'mouseout', 0, mouse.buttons);
              for (const element of previousPath) {
                if (nextPath.includes(element)) break;
                firePointer(leaveHit, element, 'pointerleave', 0, mouse.buttons, false);
                fireMouse(leaveHit, element, 'mouseleave', 0, mouse.buttons, 0, false);
              }
            }
            if (next) {
              firePointer(hit, next, 'pointerover', 0, mouse.buttons);
              fireMouse(hit, next, 'mouseover', 0, mouse.buttons);
              const entering = [];
              for (const element of nextPath) {
                if (previousPath.includes(element)) break;
                entering.push(element);
              }
              for (const element of entering.reverse()) {
                firePointer(hit, element, 'pointerenter', 0, mouse.buttons, false);
                fireMouse(hit, element, 'mouseenter', 0, mouse.buttons, 0, false);
              }
            }
            mouse.hoverPath = nextPath;
          };

          const isEditableElement = (element) => {
            if (!element || element.nodeType !== 1) return false;
            if (element.tagName === 'TEXTAREA' || element.isContentEditable) return true;
            if (element.tagName !== 'INPUT') return false;
            const nonText = [
              'button', 'checkbox', 'color', 'file', 'hidden', 'image',
              'radio', 'range', 'reset', 'submit'
            ];
            return !nonText.includes(String(element.type || '').toLowerCase());
          };

          // inputmode="none" keeps the system keyboard hidden while the element
          // still takes focus and receives forwarded keys.
          const keyboardBlocked = () => mouseMode.enabled || mouseMode.keyboardLocked;

          const suppressKeyboardFor = (element) => {
            if (!keyboardBlocked() || !isEditableElement(element)) return;
            if (!mouseMode.inputModes.has(element)) {
              mouseMode.inputModes.set(element, element.getAttribute('inputmode'));
            }
            if (element.getAttribute('inputmode') !== 'none') {
              element.setAttribute('inputmode', 'none');
            }
          };

          const suppressKeyboardInDocument = () => {
            if (!keyboardBlocked()) return;
            for (const element of document.querySelectorAll(
              'textarea, input, [contenteditable]'
            )) {
              suppressKeyboardFor(element);
            }
            suppressKeyboardFor(deepestActiveElement(document));
          };

          const restoreKeyboardInputModes = () => {
            for (const [element, previous] of mouseMode.inputModes) {
              if (previous === null) {
                element.removeAttribute('inputmode');
              } else {
                element.setAttribute('inputmode', previous);
              }
            }
            mouseMode.inputModes.clear();
          };

          const focusFromMouse = (target) => {
            const selector = 'input, textarea, select, button, a[href], [tabindex], '
              + '[contenteditable=""], [contenteditable="true"]';
            for (const element of composedAncestors(target)) {
              if (!element.matches?.(selector) || element.disabled) continue;
              suppressKeyboardFor(element);
              try {
                element.focus({ preventScroll: true });
              } catch (_) {}
              return;
            }
          };

          const commonMouseTarget = (first, second) => {
            if (!first || !second || !first.isConnected) return null;
            for (const element of composedAncestors(first)) {
              if (element === second || element.contains(second)) return element;
            }
            return null;
          };

          const mouseAction = (action, x, y, button = 0) => {
            mouse.lastX = x;
            mouse.lastY = y;
            const hit = mouseHitTest(x, y)
              || (mouse.lastHit?.element?.isConnected ? mouse.lastHit : null);
            if (!hit) {
              if (action === 'up') mouse.buttons &= ~mouseButtonMask(button);
              return false;
            }
            installPointerCapture(hit.view);
            const captured = mouse.captureTarget?.isConnected ? mouse.captureTarget : null;
            if (!captured) updateMouseHover(hit);
            mouse.lastHit = hit;
            const target = captured || hit.element;

            if (action === 'move') {
              firePointer(hit, target, 'pointermove', -1, mouse.buttons);
              fireMouse(hit, target, 'mousemove', 0, mouse.buttons);
              return true;
            }

            if (action === 'down') {
              if (button === 0) {
                const now = performance.now();
                const nearPrevious = Math.hypot(x - mouse.lastDownX, y - mouse.lastDownY) < 8;
                mouse.clickCount = nearPrevious && now - mouse.lastDownAt < 500
                  ? Math.min(mouse.clickCount + 1, 3)
                  : 1;
                mouse.lastDownAt = now;
                mouse.lastDownX = x;
                mouse.lastDownY = y;
              }
              suppressKeyboardInDocument();
              mouse.captureTarget = null;
              mouse.buttons |= mouseButtonMask(button);
              mouse.downTargets[button] = target;
              rememberTarget(target);
              const detail = button === 0 ? mouse.clickCount : 1;
              const pointerAllowed = firePointer(hit, target, 'pointerdown', button, mouse.buttons);
              const mouseAllowed = fireMouse(
                hit,
                target,
                'mousedown',
                button,
                mouse.buttons,
                detail
              );
              if (button === 0 && pointerAllowed && mouseAllowed) focusFromMouse(target);
              return true;
            }

            if (action === 'up') {
              if (!(mouse.buttons & mouseButtonMask(button))) return false;
              mouse.buttons &= ~mouseButtonMask(button);
              const detail = button === 0 ? mouse.clickCount : 1;
              firePointer(hit, target, 'pointerup', button, mouse.buttons);
              fireMouse(hit, target, 'mouseup', button, mouse.buttons, detail);
              if (!mouse.buttons) mouse.captureTarget = null;
              const downTarget = mouse.downTargets[button];
              delete mouse.downTargets[button];
              if (button === 0) {
                const clickTarget = commonMouseTarget(downTarget, target);
                if (clickTarget) {
                  fireMouse(hit, clickTarget, 'click', 0, mouse.buttons, detail);
                  if (detail === 2) {
                    fireMouse(hit, clickTarget, 'dblclick', 0, mouse.buttons, 2);
                  }
                }
              } else if (button === 2) {
                fireMouse(hit, target, 'contextmenu', 2, mouse.buttons, 1);
              }
              return true;
            }
            return false;
          };

          const scrollableAncestor = (element, deltaX, deltaY) => {
            for (const candidate of composedAncestors(element)) {
              const style = candidate.ownerDocument.defaultView.getComputedStyle(candidate);
              const scrollY = deltaY
                && /(auto|scroll|overlay)/.test(style.overflowY)
                && candidate.scrollHeight > candidate.clientHeight;
              const scrollX = deltaX
                && /(auto|scroll|overlay)/.test(style.overflowX)
                && candidate.scrollWidth > candidate.clientWidth;
              if (scrollY || scrollX) return candidate;
            }
            return element.ownerDocument.scrollingElement;
          };

          const mouseWheel = (x, y, deltaX, deltaY) => {
            const hit = mouseHitTest(x, y);
            if (!hit) return;
            const eventWindow = hit.view;
            const allowed = hit.element.dispatchEvent(new eventWindow.WheelEvent('wheel', {
              ...mouseEventInit(hit, 0, mouse.buttons, 0, true),
              deltaX,
              deltaY,
              deltaMode: 0,
              // Chromium leaves the legacy wheelDelta at 0 for synthetic events and
              // Monaco prefers it; 2.4x maps one finger pixel to one editor pixel.
              wheelDeltaX: -deltaX * 2.4,
              wheelDeltaY: -deltaY * 2.4
            }));
            // Untrusted wheel events never scroll natively, so scroll plain pages here.
            if (allowed) scrollableAncestor(hit.element, deltaX, deltaY)?.scrollBy(deltaX, deltaY);
          };

          const visualViewportRect = () => {
            const viewport = window.visualViewport;
            return {
              left: viewport ? viewport.offsetLeft : 0,
              top: viewport ? viewport.offsetTop : 0,
              width: viewport ? viewport.width : window.innerWidth,
              height: viewport ? viewport.height : window.innerHeight
            };
          };

          const ensureMouseOverlay = () => {
            if (mouseMode.host) {
              if (!mouseMode.host.isConnected) document.documentElement.appendChild(mouseMode.host);
              return;
            }
            const host = document.createElement('div');
            host.setAttribute('data-code-server-app-mouse', '');
            Object.assign(host.style, {
              position: 'fixed',
              left: '0',
              top: '0',
              width: '0',
              height: '0',
              margin: '0',
              padding: '0',
              border: '0',
              zIndex: '2147483647',
              pointerEvents: 'none',
              display: 'none'
            });
            const root = host.attachShadow({ mode: 'open' });
            root.innerHTML = `<style>
              .cursor {
                position: absolute; left: 0; top: 0; width: 14px; height: 22px;
                transform-origin: 0 0; pointer-events: none; overflow: visible;
              }
              .button {
                position: absolute; box-sizing: border-box; border-radius: 50%;
                display: flex; align-items: center; justify-content: center;
                font-family: system-ui, sans-serif; font-weight: 700; color: #fff;
                background: rgba(0, 0, 0, 0.31); border: 2px solid rgba(255, 255, 255, 0.6);
                pointer-events: none; touch-action: none;
                user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;
              }
              .button.pressed { background: rgba(103, 80, 164, 0.67); }
              .button.locked { background: rgba(103, 80, 164, 0.86); border-color: #fff; }
              .button.lock { font-weight: 400; }
            </style>
            <svg class="cursor" viewBox="0 0 14 22">
              <path d="M0.7 0.7 L0.7 18.5 L5.2 14.3 L8.3 21 L11.3 19.7 L8.2 13.1 L13.7 13.1 Z"
                fill="#fff" stroke="#000" stroke-width="1.4" stroke-linejoin="round"/>
            </svg>
            <div class="button left">L</div>
            <div class="button right">R</div>
            <div class="button lock" title="Hold the left button">🔓</div>`;
            mouseMode.host = host;
            mouseMode.cursor = root.querySelector('.cursor');
            mouseMode.left = root.querySelector('.left');
            mouseMode.right = root.querySelector('.right');
            mouseMode.lockButton = root.querySelector('.lock');
            document.documentElement.appendChild(host);
          };

          const updateMouseCursor = () => {
            if (!mouseMode.cursor) return;
            const rect = visualViewportRect();
            mouseMode.cursor.style.display = mouseMode.cursorX < 0 ? 'none' : 'block';
            mouseMode.cursor.style.transform = `translate(${mouseMode.cursorX - rect.left}px, `
              + `${mouseMode.cursorY - rect.top}px) scale(${mouseMode.scale})`;
          };

          const updateMouseButtons = () => {
            if (!mouseMode.left) return;
            const leftLocked = mouseMode.leftLocked || mouseMode.leftLockArmed;
            mouseMode.left.classList.toggle(
              'pressed',
              mouseMode.leftHeld || mouseMode.leftUnlockPending
            );
            mouseMode.left.classList.toggle('locked', leftLocked);
            mouseMode.left.textContent = leftLocked ? 'L🔒' : 'L';
            mouseMode.right.classList.toggle('pressed', mouseMode.rightHeld);
            mouseMode.lockButton.classList.toggle('locked', mouseMode.leftLocked);
            mouseMode.lockButton.textContent = mouseMode.leftLocked ? '🔒' : '🔓';
          };

          const layoutMouseOverlay = () => {
            if (!mouseMode.enabled || !mouseMode.host) return;
            ensureMouseOverlay();
            const rect = visualViewportRect();
            // Size controls in native points so they stay finger-sized at any page zoom.
            const scale = mouseMode.viewWidth > 0 ? rect.width / mouseMode.viewWidth : 1;
            mouseMode.scale = scale;
            Object.assign(mouseMode.host.style, {
              display: 'block',
              left: `${rect.left}px`,
              top: `${rect.top}px`,
              width: `${rect.width}px`,
              height: `${rect.height}px`
            });
            const place = (element, size, right, bottom) => Object.assign(element.style, {
              width: `${size * scale}px`,
              height: `${size * scale}px`,
              right: `${right * scale}px`,
              bottom: `${bottom * scale}px`,
              fontSize: `${18 * scale}px`,
              borderWidth: `${2 * scale}px`
            });
            place(mouseMode.left, 68, 84, 40);
            place(mouseMode.right, 56, 16, 16);
            place(mouseMode.lockButton, 42, 97, 120);
            mouseMode.lockButton.style.fontSize = `${16 * scale}px`;
            if (mouseMode.cursorX < 0) {
              mouseMode.cursorX = rect.left + rect.width / 2;
              mouseMode.cursorY = rect.top + rect.height / 2;
            }
            updateMouseCursor();
          };

          const moveMouseCursor = (x, y) => {
            const rect = visualViewportRect();
            x = Math.min(Math.max(x, rect.left), rect.left + rect.width - 1);
            y = Math.min(Math.max(y, rect.top), rect.top + rect.height - 1);
            mouseMode.cursorX = x;
            mouseMode.cursorY = y;
            if (mouseMode.leftHeld) mouseMode.leftMoved = true;
            updateMouseCursor();
            mouseAction('move', x, y);
          };

          const clearLeftLockTimer = () => {
            if (mouseMode.lockTimer) window.clearTimeout(mouseMode.lockTimer);
            mouseMode.lockTimer = 0;
          };

          const mouseLeftDown = () => {
            if (mouseMode.leftLocked) {
              // Tap while drag-locked: release the button when this tap ends.
              mouseMode.leftLocked = false;
              mouseMode.leftUnlockPending = true;
              updateMouseButtons();
              return;
            }
            mouseMode.leftHeld = true;
            mouseMode.leftLockArmed = false;
            mouseMode.leftMoved = false;
            mouseAction('down', mouseMode.cursorX, mouseMode.cursorY, 0);
            clearLeftLockTimer();
            mouseMode.lockTimer = window.setTimeout(() => {
              mouseMode.lockTimer = 0;
              if (!mouseMode.leftHeld || mouseMode.leftMoved) return;
              mouseMode.leftLockArmed = true;
              navigator.vibrate?.(15);
              updateMouseButtons();
            }, 500);
            updateMouseButtons();
          };

          const mouseLeftUp = (cancelled) => {
            clearLeftLockTimer();
            if (mouseMode.leftUnlockPending) {
              mouseMode.leftUnlockPending = false;
              mouseMode.leftHeld = false;
              mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, 0);
              updateMouseButtons();
              return;
            }
            if (mouseMode.leftLocked) {
              // Locked with the lock button while L was pressed: keep holding.
              updateMouseButtons();
              return;
            }
            if (!mouseMode.leftHeld) return;
            if (!cancelled && mouseMode.leftLockArmed && !mouseMode.leftMoved) {
              // Long press without movement: keep the button down for one-finger drags.
              mouseMode.leftLockArmed = false;
              mouseMode.leftLocked = true;
              updateMouseButtons();
              return;
            }
            mouseMode.leftHeld = false;
            mouseMode.leftLockArmed = false;
            mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, 0);
            updateMouseButtons();
          };

          // The lock button holds the left button down (for drags and selections)
          // until it, or L, is tapped again.
          const toggleLeftLock = () => {
            clearLeftLockTimer();
            mouseMode.leftLockArmed = false;
            mouseMode.leftUnlockPending = false;
            if (mouseMode.leftLocked) {
              mouseMode.leftLocked = false;
              if (mouseMode.leftHeld) {
                mouseMode.leftHeld = false;
                mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, 0);
              }
            } else {
              mouseMode.leftLocked = true;
              if (!mouseMode.leftHeld) {
                mouseMode.leftHeld = true;
                mouseMode.leftMoved = false;
                mouseAction('down', mouseMode.cursorX, mouseMode.cursorY, 0);
              }
            }
            navigator.vibrate?.(15);
            updateMouseButtons();
          };

          const releaseMouseButtons = () => {
            clearLeftLockTimer();
            for (const held of [0, 2, 1]) {
              if (mouse.buttons & mouseButtonMask(held)) {
                mouseAction('up', mouse.lastX, mouse.lastY, held);
              }
            }
            mouseMode.leftHeld = false;
            mouseMode.leftLocked = false;
            mouseMode.leftLockArmed = false;
            mouseMode.leftUnlockPending = false;
            mouseMode.rightHeld = false;
            mouseMode.touches.clear();
            updateMouseButtons();
          };

          const pointInElement = (element, x, y) => {
            if (!element) return false;
            const rect = element.getBoundingClientRect();
            return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
          };

          // Controls the page marks as app UI (e.g. the remote desktop's file
          // bars) keep normal touch behaviour in mouse mode.
          const appUiTouchIds = new Set();
          const isAppUiEvent = (event) => {
            const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
            return path.some((node) => node?.hasAttribute?.('data-your-workspace-ui'));
          };

          const handleMouseModeTouch = (event) => {
            if (!mouseMode.enabled) return;
            const changed = Array.from(event.changedTouches || []);
            if (event.type === 'touchstart' && isAppUiEvent(event)) {
              for (const touch of changed) appUiTouchIds.add(touch.identifier);
              return;
            }
            if (changed.length && changed.every((touch) => appUiTouchIds.has(touch.identifier))) {
              if (event.type === 'touchend' || event.type === 'touchcancel') {
                for (const touch of changed) appUiTouchIds.delete(touch.identifier);
              }
              return;
            }
            if (event.cancelable) event.preventDefault();
            event.stopImmediatePropagation();
            const type = event.type;
            for (const touch of Array.from(event.changedTouches || [])) {
              const x = touch.clientX;
              const y = touch.clientY;

              if (type === 'touchstart') {
                const pageTouches = Array.from(mouseMode.touches.values())
                  .filter((info) => info.role === 'cursor' || info.role === 'anchor');
                let role = 'cursor';
                if (pointInElement(mouseMode.lockButton, x, y)) {
                  role = 'lock';
                } else if (pointInElement(mouseMode.left, x, y)) {
                  role = 'left';
                } else if (pointInElement(mouseMode.right, x, y)) {
                  role = 'right';
                } else if (pageTouches.length) {
                  // A second finger on the page scrolls; the first one stops steering.
                  role = 'scroll';
                  for (const info of pageTouches) {
                    info.role = 'anchor';
                  }
                }
                mouseMode.touches.set(touch.identifier, {
                  role,
                  startX: x,
                  startY: y,
                  lastX: x,
                  lastY: y,
                  startedAt: performance.now(),
                  moved: false
                });
                if (role === 'lock') {
                  toggleLeftLock();
                } else if (role === 'left') {
                  mouseLeftDown();
                } else if (role === 'right') {
                  mouseMode.rightHeld = true;
                  mouseAction('down', mouseMode.cursorX, mouseMode.cursorY, 2);
                  updateMouseButtons();
                }
                continue;
              }

              const info = mouseMode.touches.get(touch.identifier);
              if (!info) continue;

              if (type === 'touchmove') {
                if (Math.hypot(x - info.startX, y - info.startY) > 10 * mouseMode.scale) {
                  info.moved = true;
                }
                if (info.role === 'cursor') {
                  // Relative movement, a little faster for quick swipes.
                  const dx = x - info.lastX;
                  const dy = y - info.lastY;
                  const distance = Math.hypot(dx, dy) / (mouseMode.scale || 1);
                  const gain = Math.min(2.5, 1 + Math.max(0, distance - 4) * 0.08);
                  moveMouseCursor(mouseMode.cursorX + dx * gain, mouseMode.cursorY + dy * gain);
                } else if (info.role === 'scroll') {
                  mouseWheel(mouseMode.cursorX, mouseMode.cursorY, info.lastX - x, info.lastY - y);
                }
                info.lastX = x;
                info.lastY = y;
                continue;
              }

              mouseMode.touches.delete(touch.identifier);
              const cancelled = type === 'touchcancel';
              if (info.role === 'left') {
                mouseLeftUp(cancelled);
              } else if (info.role === 'right') {
                if (mouseMode.rightHeld) {
                  mouseMode.rightHeld = false;
                  mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, 2);
                  updateMouseButtons();
                }
              } else if (info.role === 'cursor'
                  && !cancelled
                  && !info.moved
                  && !mouse.buttons
                  && performance.now() - info.startedAt < 350) {
                // A quick tap is a left click at the cursor.
                mouseAction('down', mouseMode.cursorX, mouseMode.cursorY, 0);
                mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, 0);
              }
            }
          };

          // Keep real touch-derived pointer and mouse events away from the page so
          // only the emulated mouse reaches it.
          const blockNativePointerEvents = (event) => {
            if (!mouseMode.enabled || !event.isTrusted || isAppUiEvent(event)) return;
            if (event.pointerType === 'mouse' || event.pointerType === 'pen') return;
            event.stopImmediatePropagation();
            if (event.cancelable) event.preventDefault();
          };

          for (const type of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) {
            window.addEventListener(type, handleMouseModeTouch, { capture: true, passive: false });
          }
          for (const type of [
            'pointerdown', 'pointermove', 'pointerup', 'pointercancel',
            'mousedown', 'mousemove', 'mouseup', 'click', 'dblclick', 'contextmenu'
          ]) {
            window.addEventListener(type, blockNativePointerEvents, true);
          }
          document.addEventListener('focusin', (event) => {
            const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
            suppressKeyboardFor(path[0] || event.target);
          }, true);
          window.visualViewport?.addEventListener('resize', layoutMouseOverlay);
          window.visualViewport?.addEventListener('scroll', layoutMouseOverlay);

          const setMouseMode = (enabled, viewWidth) => {
            if (viewWidth > 0) mouseMode.viewWidth = viewWidth;
            if (enabled === mouseMode.enabled) {
              layoutMouseOverlay();
              return true;
            }
            mouseMode.enabled = enabled;
            if (enabled) {
              ensureMouseOverlay();
              layoutMouseOverlay();
              updateMouseButtons();
              suppressKeyboardInDocument();
            } else {
              releaseMouseButtons();
              if (mouseMode.host) mouseMode.host.style.display = 'none';
              if (!mouseMode.keyboardLocked) restoreKeyboardInputModes();
            }
            return true;
          };

          const setKeyboardLocked = (locked) => {
            if (locked === mouseMode.keyboardLocked) return true;
            mouseMode.keyboardLocked = locked;
            if (locked) {
              suppressKeyboardInDocument();
            } else if (!mouseMode.enabled) {
              restoreKeyboardInputModes();
            }
            return true;
          };

          // Keyboard locked open on a remote desktop page: Chromium hides the
          // keyboard whenever a tap lands on something it cannot type into,
          // such as the IronRDP canvas, and the app then brings it back (a
          // visible flash). Marked editable, the canvas keeps it up; typing
          // still goes through the app's own input connection to IronRDP.
          let keyboardHeldOpen = false;
          const applyKeyboardHeldOpen = () => {
            if (!window.__yourWorkspaceRdpPage) return;
            const canvas = findIronRdpCanvas();
            if (!canvas) return;
            if (keyboardHeldOpen) {
              if (canvas.getAttribute('contenteditable') !== 'true') {
                canvas.setAttribute('contenteditable', 'true');
                canvas.setAttribute('spellcheck', 'false');
                canvas.style.caretColor = 'transparent';
                canvas.style.outline = 'none';
              }
            } else if (canvas.hasAttribute('contenteditable')) {
              canvas.removeAttribute('contenteditable');
            }
          };
          // The canvas is replaced on reconnect: re-apply before each touch.
          document.addEventListener('pointerdown', () => {
            if (keyboardHeldOpen) applyKeyboardHeldOpen();
          }, true);
          const setKeyboardHeldOpen = (held) => {
            keyboardHeldOpen = held;
            applyKeyboardHeldOpen();
            return true;
          };

          const bridge = {
            version: 17,
            forceKeyboard,
            installRdpGestures,
            installDesktopGestures,
            sendKey(key, code, keyCode) {
              dispatchCompleteKey(key, code, keyCode);
              return true;
            },
            sendText(text) {
              forwardText(String(text || ''));
              return true;
            },
            sendShortcut(key, code, keyCode, control, shift) {
              return dispatchShortcut(key, code, keyCode, control, shift);
            },
            setMouseMode(enabled, viewWidth) {
              return setMouseMode(Boolean(enabled), Number(viewWidth) || 0);
            },
            setKeyboardLocked(locked) {
              return setKeyboardLocked(Boolean(locked));
            },
            setKeyboardHeldOpen(held) {
              return setKeyboardHeldOpen(Boolean(held));
            },
            setModifiers(control, shift) {
              const nextControl = Boolean(control);
              const nextShift = Boolean(shift);
              const controlChanged = state.control !== nextControl;
              const shiftChanged = state.shift !== nextShift;
              state.control = nextControl;
              state.shift = nextShift;

              if (controlChanged) {
                dispatchModifier('Control', 'ControlLeft', 17, nextControl);
              }
              if (shiftChanged) {
                dispatchModifier('Shift', 'ShiftLeft', 16, nextShift);
              }
            }
          };
          window.__codeServerAppKeyboard = bridge;
          window.__codeServerAppForceKeyboard = () => bridge.forceKeyboard();
        })();
        """;

    private SharedPreferences preferences;
    private final List<ProjectProfile> projects = new ArrayList<>();
    private final Map<String, ProjectSession> projectSessions = new LinkedHashMap<>();
    private final Map<WebView, Integer> appliedLayoutZoomSteps = new WeakHashMap<>();
    private final Map<WebView, String> lastFinishedUrls = new WeakHashMap<>();
    private LinearLayout rootContainer;
    private LinearLayout addressBar;
    private EditText addressField;
    private FrameLayout webContainer;
    private RdpConnectionPanel rdpPanel;
    private RdpPageBridge rdpPageBridge;
    private final Set<WebView> rdpWebViews = Collections.newSetFromMap(new WeakHashMap<>());
    private FrameLayout contentFrame;
    private Button disconnectButton;
    private Button uploadButton;
    private WebView uploadTarget;
    private LinearLayout zoomOverlay;
    private TextView zoomPercentLabel;
    private SeekBar zoomSlider;
    private boolean zoomSliderTracking;
    private WebView webView;
    private String activeSessionKey;
    private Button controlButton;
    private Button shiftButton;
    private Button mouseModeButton;
    private Button keyboardLockButton;
    private Button fullscreenButton;
    private boolean fullscreenEnabled;
    /** Keyboard lock: KEYBOARD_UNLOCKED, KEYBOARD_LOCKED_OPEN or KEYBOARD_LOCKED_HIDDEN. */
    private int keyboardLock = KEYBOARD_UNLOCKED;
    private boolean imeShown;
    /** IME height kept as padding while a locked-open keyboard is being brought back. */
    private int heldImeBottom;
    private long imeHoldStartedAt;
    private boolean controlLocked;
    private boolean shiftLocked;
    private boolean keepAliveEnabled;
    private boolean mouseModeEnabled;
    private int layoutZoomSteps;
    private final Handler keepAliveHandler = new Handler(Looper.getMainLooper());
    private final Handler addressBarHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideAddressBar = () -> {
        if (addressBar == null || addressBar.getVisibility() != View.VISIBLE) {
            return;
        }
        if ((addressField != null && addressField.hasFocus()) || zoomSliderTracking) {
            // Keep the bar while an address is typed or zoom is dragged; both reschedule.
            return;
        }
        if (webView == null || webView.getUrl() == null) {
            return;
        }
        if (!fullscreenEnabled) {
            // The address bar stays outside fullscreen; only the zoom slider hides.
            if (zoomOverlay != null) {
                zoomOverlay.setVisibility(View.GONE);
            }
            return;
        }
        hideAddressBar();
    };
    private final Runnable sessionKeepAlivePulse = new Runnable() {
        @Override
        public void run() {
            if (!keepAliveEnabled || isDestroyed()) {
                return;
            }
            boolean activeViewIsCached = activeSessionKey != null;
            for (ProjectSession session : projectSessions.values()) {
                pulseWebView(session.webView);
            }
            if (!activeViewIsCached) {
                pulseWebView(webView);
            }
            keepAliveHandler.postDelayed(this, SESSION_KEEP_ALIVE_PULSE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        preferences.edit().remove(LEGACY_NATIVE_ZOOM_PERCENT_KEY).apply();
        if (!preferences.getBoolean(VIEWPORT_RELOAD_ZOOM_MIGRATED_KEY, false)) {
            preferences.edit()
                .putInt(LAYOUT_ZOOM_STEPS_KEY, 0)
                .putBoolean(VIEWPORT_RELOAD_ZOOM_MIGRATED_KEY, true)
                .apply();
        }
        layoutZoomSteps = Math.max(
            MIN_LAYOUT_ZOOM_STEPS,
            Math.min(
                MAX_LAYOUT_ZOOM_STEPS,
                preferences.getInt(LAYOUT_ZOOM_STEPS_KEY, 0)
            )
        );
        keepAliveEnabled = preferences.getBoolean(KEEP_ALIVE_KEY, false);
        mouseModeEnabled = preferences.getBoolean(MOUSE_MODE_KEY, false);
        fullscreenEnabled = preferences.getBoolean(FULLSCREEN_KEY, false);
        loadProjects();
        setContentView(createContentView());
        configureSystemUi();
        applyKeepAliveMode();

        rdpPanel = new RdpConnectionPanel(
            this,
            (address, username, password) -> openRdpSession(address, username, password, true)
        );
        rdpPageBridge = new RdpPageBridge(this, this::onRdpSessionEvent);
        String savedAddress = preferences.getString(ADDRESS_KEY, "");
        addressField.setText(savedAddress);
        if (savedAddress == null || savedAddress.trim().isEmpty()) {
            showBlankWebView();
            addressField.requestFocus();
        } else if (RdpConnectionPanel.isRdpAddress(savedAddress)) {
            showBlankWebView();
            switchToProjectUrl(savedAddress);
        } else {
            switchToProjectUrl(savedAddress);
        }
        openRdpPanelFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openRdpPanelFromIntent(intent);
    }

    /** The tunnel notification reopens the connection panel for its host. */
    private void openRdpPanelFromIntent(Intent intent) {
        String host = intent == null
            ? null
            : intent.getStringExtra(RdpTunnelService.EXTRA_OPEN_RDP_HOST);
        if (host != null && !host.isEmpty()) {
            intent.removeExtra(RdpTunnelService.EXTRA_OPEN_RDP_HOST);
            rdpPanel.show("rdp://" + host);
        }
    }

    private View createContentView() {
        EdgeGestureLayout root = new EdgeGestureLayout(this);
        rootContainer = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applySafeAreaInsets(view, insets);
            return insets;
        });

        addressBar = new LinearLayout(this);
        addressBar.setOrientation(LinearLayout.HORIZONTAL);
        addressBar.setGravity(Gravity.CENTER_VERTICAL);
        addressBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        addressBar.setBackgroundColor(Color.rgb(243, 243, 243));

        Button projectsButton = createToolbarButton("☰");
        projectsButton.setContentDescription("Switch code-server project");
        projectsButton.setOnClickListener(view -> showProjectSwitcher());
        addressBar.addView(projectsButton);

        addressField = new EditText(this);
        addressField.setSingleLine(true);
        addressField.setTextSize(14);
        addressField.setMinHeight(0);
        addressField.setMinimumHeight(0);
        addressField.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setColor(Color.WHITE);
        fieldBackground.setCornerRadius(dp(10));
        fieldBackground.setStroke(Math.max(1, dp(1) / 2), Color.argb(30, 0, 0, 0));
        addressField.setBackground(fieldBackground);
        addressField.setHint("https://… or rdp://host");
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressField.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                addressBarHandler.removeCallbacks(autoHideAddressBar);
            } else {
                scheduleAddressBarAutoHide();
            }
        });
        addressField.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterPressed = event != null
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadEnteredAddress();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams fieldParams =
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        fieldParams.setMargins(dp(4), 0, dp(4), 0);
        addressBar.addView(addressField, fieldParams);

        Button reloadButton = createToolbarButton("↻");
        reloadButton.setContentDescription("Reload code-server");
        reloadButton.setOnClickListener(view -> webView.reload());
        addressBar.addView(reloadButton);

        uploadButton = createToolbarButton("⇪");
        uploadButton.setContentDescription("Upload files to the remote desktop");
        uploadButton.setOnClickListener(view -> pickFilesToUpload());
        uploadButton.setVisibility(View.GONE);
        addressBar.addView(uploadButton);

        disconnectButton = createToolbarButton("⏏");
        disconnectButton.setContentDescription("Disconnect the remote desktop");
        disconnectButton.setOnClickListener(view -> disconnectActiveRdpSession());
        disconnectButton.setVisibility(View.GONE);
        addressBar.addView(disconnectButton);

        fullscreenButton = createToolbarButton("⛶");
        fullscreenButton.setOnClickListener(view -> setFullscreenEnabled(!fullscreenEnabled));
        addressBar.addView(fullscreenButton);
        updateFullscreenButton();

        Button settingsButton = createToolbarButton("⚙");
        settingsButton.setContentDescription("Settings");
        settingsButton.setOnClickListener(view -> showSettings());
        addressBar.addView(settingsButton);

        root.addView(
            addressBar,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ADDRESS_BAR_HEIGHT_DP)
            )
        );
        // Raised so it can float above a remote desktop (see updateAddressBarOverlay).
        addressBar.setElevation(dp(2));

        // The zoom slider floats above the web views in a separate frame, so
        // bringing a session's WebView to the front never covers it.
        contentFrame = new FrameLayout(this);
        root.addView(
            contentFrame,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );
        webContainer = new FrameLayout(this);
        contentFrame.addView(
            webContainer,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );
        zoomOverlay = createZoomOverlay();
        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        zoomParams.topMargin = dp(8);
        contentFrame.addView(zoomOverlay, zoomParams);

        webContainer.addOnLayoutChangeListener(
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (mouseModeEnabled && right - left != oldRight - oldLeft) {
                    view.post(this::syncMouseModeAll);
                }
            }
        );

        HorizontalScrollView keyboardScroll = new HorizontalScrollView(this);
        keyboardScroll.setHorizontalScrollBarEnabled(false);
        keyboardScroll.setFillViewport(false);
        keyboardScroll.setBackgroundColor(Color.rgb(243, 243, 243));

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        keyRow.setPadding(dp(6), dp(3), dp(6), dp(3));

        Button keyboardButton = createKeyButton("KB");
        keyboardButton.setContentDescription("Force show keyboard");
        keyboardButton.setOnClickListener(view -> {
            if (keyboardLock == KEYBOARD_LOCKED_HIDDEN) {
                Toast.makeText(this, "Keyboard is locked hidden", Toast.LENGTH_SHORT).show();
                return;
            }
            forceShowKeyboard();
        });
        keyRow.addView(keyboardButton, keyLayoutParams(dp(54)));

        keyboardLockButton = createKeyButton("⌨🔓");
        keyboardLockButton.setOnClickListener(view -> toggleKeyboardLock());
        keyRow.addView(keyboardLockButton, keyLayoutParams(dp(58)));

        mouseModeButton = createKeyButton("🖱");
        mouseModeButton.setOnClickListener(view -> setMouseModeEnabled(!mouseModeEnabled));
        keyRow.addView(mouseModeButton, keyLayoutParams(dp(54)));

        controlButton = createKeyButton("Ctrl 🔓");
        controlButton.setOnClickListener(view -> {
            controlLocked = !controlLocked;
            updateModifierButtons();
            syncModifiers();
            if (!mouseModeEnabled) {
                syncModifierImeCapture();
            }
        });
        keyRow.addView(controlButton, keyLayoutParams(dp(72)));

        shiftButton = createKeyButton("Shift 🔓");
        shiftButton.setOnClickListener(view -> {
            shiftLocked = !shiftLocked;
            updateModifierButtons();
            syncModifiers();
            if (!mouseModeEnabled) {
                syncModifierImeCapture();
            }
        });
        keyRow.addView(shiftButton, keyLayoutParams(dp(76)));

        addKey(keyRow, "Esc", "Escape", "Escape", 27, dp(54));
        addKey(keyRow, "Tab", "Tab", "Tab", 9, dp(54));
        addKey(keyRow, "Enter", "Enter", "Enter", 13, dp(64));
        addKey(keyRow, "Bksp", "Backspace", "Backspace", 8, dp(64));
        addRepeatingKey(keyRow, "←", "ArrowLeft", "ArrowLeft", 37, dp(50));
        addRepeatingKey(keyRow, "↑", "ArrowUp", "ArrowUp", 38, dp(50));
        addRepeatingKey(keyRow, "↓", "ArrowDown", "ArrowDown", 40, dp(50));
        addRepeatingKey(keyRow, "→", "ArrowRight", "ArrowRight", 39, dp(50));
        addKey(keyRow, "PgUp", "PageUp", "PageUp", 33, dp(64));
        addKey(keyRow, "PgDn", "PageDown", "PageDown", 34, dp(64));

        Button controlCButton = createKeyButton("Ctrl+C");
        controlCButton.setContentDescription("Send Control C");
        controlCButton.setOnClickListener(view -> sendControlC());
        keyRow.addView(controlCButton, keyLayoutParams(dp(74)));

        keyboardScroll.addView(keyRow);
        root.addView(
            keyboardScroll,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KEY_HEIGHT_DP + 6)
            )
        );

        updateModifierButtons();
        updateKeyboardLockButton();
        applyMouseMode();
        return root;
    }

    private void configureSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Let the page extend into the status-bar strip beside a camera cutout.
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        applySystemBars();
    }

    /**
     * Fullscreen hides the system bars and lets the address bar auto-hide; the
     * regular mode keeps both visible (the zoom slider still auto-hides).
     */
    private void setFullscreenEnabled(boolean enabled) {
        fullscreenEnabled = enabled;
        preferences.edit().putBoolean(FULLSCREEN_KEY, enabled).apply();
        applySystemBars();
        updateFullscreenButton();
        if (enabled) {
            // Hide the system bars now and the address bar five seconds later.
            showAddressBarTemporarily();
        } else if (addressBar != null) {
            addressBar.setVisibility(View.VISIBLE);
            updateAddressBarOverlay();
        }
        if (rootContainer != null) {
            rootContainer.requestApplyInsets();
        }
    }

    private void updateFullscreenButton() {
        if (fullscreenButton == null) {
            return;
        }
        fullscreenButton.setContentDescription(
            fullscreenEnabled ? "Exit fullscreen" : "Enter fullscreen"
        );
        fullscreenButton.setTextColor(fullscreenEnabled ? ACCENT : Color.BLACK);
    }

    private void applySystemBars() {
        if (fullscreenEnabled) {
            hideSystemBars();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                controller.show(WindowInsets.Type.systemBars());
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars, lightBars);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        }
    }

    private void showSettings() {
        CheckBox keepAliveCheckBox = new CheckBox(this);
        keepAliveCheckBox.setText(
            "Keep sessions alive\n"
                + "Uses a foreground service, persistent notification, native WebView pulses, "
                + "and an optional 1-pixel overlay process anchor. "
                + "Keeps up to 10 open sessions connected without the 30-minute expiry. "
                + "May increase battery usage."
        );
        keepAliveCheckBox.setChecked(keepAliveEnabled);
        int padding = dp(20);
        keepAliveCheckBox.setPadding(0, dp(8), 0, dp(8));

        Button tokensButton = new Button(this);
        tokensButton.setAllCaps(false);
        tokensButton.setText("Cloudflare Access service tokens…");
        tokensButton.setOnClickListener(view -> showServiceTokenManager());

        LinearLayout settingsView = new LinearLayout(this);
        settingsView.setOrientation(LinearLayout.VERTICAL);
        settingsView.setPadding(padding, dp(4), padding, 0);
        settingsView.addView(keepAliveCheckBox);
        settingsView.addView(tokensButton);

        new AlertDialog.Builder(this)
            .setTitle(boldText("Settings"))
            .setView(settingsView)
            .setPositiveButton("Done", (dialog, which) -> {
                setKeepAliveEnabled(keepAliveCheckBox.isChecked());
            })
            .setNeutralButton("System permissions", (dialog, which) -> {
                requestKeepAlivePermissions();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Lists the saved Cloudflare Access service tokens; tap one to edit or delete it. */
    private void showServiceTokenManager() {
        List<ServiceTokenStore.ServiceToken> tokens = ServiceTokenStore.list(this);
        List<CharSequence> labels = new ArrayList<>();
        labels.add("+ Add service token");
        for (ServiceTokenStore.ServiceToken token : tokens) {
            labels.add(token.name + "  ·  " + token.maskedClientId());
        }
        new AlertDialog.Builder(this)
            .setTitle(boldText("Cloudflare Access service tokens"))
            .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which == 0) {
                    showServiceTokenEditor(null);
                } else {
                    showServiceTokenActions(tokens.get(which - 1));
                }
            })
            .setNegativeButton("Done", null)
            .show();
    }

    private void showServiceTokenActions(ServiceTokenStore.ServiceToken token) {
        new AlertDialog.Builder(this)
            .setTitle(boldText(token.name))
            .setMessage("Client ID " + token.maskedClientId()
                + "\nThe client secret is stored encrypted and is not shown.")
            .setPositiveButton("Edit", (dialog, which) -> showServiceTokenEditor(token))
            .setNeutralButton("Delete", (dialog, which) -> new AlertDialog.Builder(this)
                .setTitle(boldText("Delete " + token.name + "?"))
                .setMessage("Projects that use it go back to browser sign-in.")
                .setPositiveButton("Delete", (confirm, button) -> {
                    ServiceTokenStore.delete(this, token.id);
                    showServiceTokenManager();
                })
                .setNegativeButton("Cancel", null)
                .show())
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Adds a service token, or edits one (an empty secret keeps the saved one). */
    private void showServiceTokenEditor(ServiceTokenStore.ServiceToken existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setHint("Name, e.g. Home");
        EditText idField = new EditText(this);
        idField.setSingleLine(true);
        idField.setHint("Client ID (….access)");
        idField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText secretField = new EditText(this);
        secretField.setSingleLine(true);
        secretField.setHint(existing == null ? "Client secret" : "Client secret (leave empty to keep)");
        secretField.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        if (existing != null) {
            nameField.setText(existing.name);
            idField.setText(existing.clientId);
        }
        form.addView(nameField);
        form.addView(idField);
        form.addView(secretField);
        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setText("Stored encrypted with the Android Keystore and excluded from backups.");
        form.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(boldText(existing == null ? "Add service token" : "Edit service token"))
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                String name = nameField.getText().toString().trim();
                String clientId = idField.getText().toString().trim();
                String secret = secretField.getText().toString().trim();
                if (secret.isEmpty() && existing != null) {
                    secret = existing.clientSecret;
                }
                if (clientId.isEmpty() || secret.isEmpty()) {
                    Toast.makeText(this, "Enter the client ID and secret", Toast.LENGTH_SHORT)
                        .show();
                    return;
                }
                if (name.isEmpty()) {
                    name = "Service token " + (ServiceTokenStore.list(this).size() + 1);
                }
                ServiceTokenStore.save(
                    this,
                    existing == null ? null : existing.id,
                    name,
                    clientId,
                    secret
                );
                dialog.dismiss();
                Toast.makeText(this, "Service token saved", Toast.LENGTH_SHORT).show();
                showServiceTokenManager();
            }));
        dialog.show();
    }

    private void setKeepAliveEnabled(boolean enabled) {
        if (keepAliveEnabled == enabled) {
            return;
        }
        keepAliveEnabled = enabled;
        preferences.edit().putBoolean(KEEP_ALIVE_KEY, enabled).apply();
        applyKeepAliveMode();
        updateWebViewRendererPriority();
        updateSessionKeepAlivePulse();
        if (enabled) {
            requestKeepAlivePermissions();
        }
        if (!enabled) {
            cleanupExpiredProjectSessions(SystemClock.elapsedRealtime());
        }
        Toast.makeText(
            this,
            enabled ? "Session keep-alive enabled" : "Session keep-alive disabled",
            Toast.LENGTH_SHORT
        ).show();
    }

    /**
     * Runs the foreground keep-alive service while session keep-alive is on or a
     * remote desktop is open: without it Android freezes the app in the
     * background, which stops the remote desktop gateway and drops the session.
     */
    private void applyKeepAliveMode() {
        boolean remoteDesktopOpen = !rdpWebViews.isEmpty();
        KeepAliveService.remoteDesktopActive = remoteDesktopOpen;
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (keepAliveEnabled || remoteDesktopOpen) {
            startForegroundService(serviceIntent);
        } else {
            stopService(serviceIntent);
        }
    }

    private void requestKeepAlivePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] { Manifest.permission.POST_NOTIFICATIONS },
                NOTIFICATION_PERMISSION_REQUEST
            );
            return;
        }
        requestAggressiveKeepAlivePermissions();
    }

    private void requestAggressiveKeepAlivePermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Intent overlayIntent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(overlayIntent, OVERLAY_PERMISSION_REQUEST);
            return;
        }
        requestBatteryOptimizationExemption();
    }

    private void requestBatteryOptimizationExemption() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null
            || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            Intent batteryIntent = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())
            );
            startActivity(batteryIntent);
        } catch (RuntimeException exception) {
            Toast.makeText(
                this,
                "Open system battery settings and allow unrestricted background use",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateSessionKeepAlivePulse() {
        keepAliveHandler.removeCallbacks(sessionKeepAlivePulse);
        if (keepAliveEnabled) {
            keepAliveHandler.post(sessionKeepAlivePulse);
        }
    }

    private void updateWebViewRendererPriority() {
        boolean activeViewIsCached = activeSessionKey != null;
        for (ProjectSession session : projectSessions.values()) {
            applyWebViewRendererPriority(session.webView);
        }
        if (!activeViewIsCached) {
            applyWebViewRendererPriority(webView);
        }
    }

    private void applyWebViewRendererPriority(WebView target) {
        if (target == null) {
            return;
        }
        boolean important = keepAliveEnabled || rdpWebViews.contains(target);
        target.setRendererPriorityPolicy(
            important
                ? WebView.RENDERER_PRIORITY_IMPORTANT
                : WebView.RENDERER_PRIORITY_BOUND,
            !important
        );
    }

    private void pulseWebView(WebView target) {
        if (target == null) {
            return;
        }
        try {
            target.evaluateJavascript(KEEP_ALIVE_PULSE_SCRIPT, null);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            requestAggressiveKeepAlivePermissions();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            applyKeepAliveMode();
            requestBatteryOptimizationExemption();
        } else if (requestCode == UPLOAD_FILES_REQUEST) {
            WebView target = uploadTarget;
            uploadTarget = null;
            if (resultCode == RESULT_OK && data != null && target != null
                && rdpWebViews.contains(target)) {
                offerUploads(target, data);
            }
        }
    }

    /**
     * Picks device files for the remote desktop in front. They are put on the
     * remote clipboard, to be pasted (Ctrl+V) in Windows.
     */
    private void pickFilesToUpload() {
        if (webView == null || !rdpWebViews.contains(webView)) {
            return;
        }
        uploadTarget = webView;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, UPLOAD_FILES_REQUEST);
        } catch (android.content.ActivityNotFoundException exception) {
            uploadTarget = null;
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    private void offerUploads(WebView target, Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                uris.add(data.getClipData().getItemAt(index).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        RdpGateway gateway;
        try {
            gateway = RdpGateway.get(this);
        } catch (IOException exception) {
            Toast.makeText(this, "Could not prepare the upload", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONArray items = new JSONArray();
        for (Uri uri : uris) {
            String name = "file";
            long size = -1L;
            try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[] {
                    android.provider.OpenableColumns.DISPLAY_NAME,
                    android.provider.OpenableColumns.SIZE
                },
                null,
                null,
                null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) {
                        name = cursor.getString(0);
                    }
                    if (!cursor.isNull(1)) {
                        size = cursor.getLong(1);
                    }
                }
            } catch (RuntimeException ignored) {
                // Keep the defaults.
            }
            String id = gateway.stageUpload(
                () -> {
                    java.io.InputStream stream = getContentResolver().openInputStream(uri);
                    if (stream == null) {
                        throw new IOException("Could not open " + uri);
                    }
                    return stream;
                },
                size
            );
            try {
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("name", DownloadSaver.safeName(name));
                item.put("size", size);
                items.put(item);
            } catch (Exception ignored) {
                // Plain values.
            }
        }
        target.evaluateJavascript(
            "window.__rdpUploadFromApp ? (window.__rdpUploadFromApp(" + items + "), true) : false",
            value -> {
                if (!"true".equals(value)) {
                    Toast.makeText(this, "The remote desktop is not ready", Toast.LENGTH_SHORT)
                        .show();
                }
            }
        );
    }

    /**
     * Fullscreen runs in sticky immersive mode. An edge swipe then
     * only shows translucent, temporary system bars (never the notification shade)
     * and is still delivered to the app, which answers the first swipe with its own
     * address bar and dismisses the system bars again; see EdgeGestureLayout.
     */
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && keyboardLock == KEYBOARD_LOCKED_OPEN && !imeShown) {
            addressBarHandler.removeCallbacks(reshowLockedKeyboard);
            addressBarHandler.postDelayed(reshowLockedKeyboard, 300L);
        }
        if (hasFocus) {
            // Dialogs and other windows can bring the system bars back.
            applySystemBars();
        }
    }

    private void applySafeAreaInsets(View view, WindowInsets insets) {
        boolean imeVisible;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            imeVisible = insets.isVisible(WindowInsets.Type.ime()) || ime.bottom > 0;

            // System bars are hidden and the page fills the top edge, including the
            // cutout strip. Only the address bar steps below a top cutout, and side
            // cutouts in landscape keep their padding.
            Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            // Outside fullscreen the visible system bars also take their space.
            Insets bars = fullscreenEnabled
                ? Insets.NONE
                : insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(
                Math.max(cutout.left, bars.left),
                0,
                Math.max(cutout.right, bars.right),
                Math.max(Math.max(cutout.bottom, bars.bottom), heldKeyboardInset(ime.bottom))
            );
            applyAddressBarTopInset(Math.max(cutout.top, bars.top));
        } else {
            int bottomInset = insets.getSystemWindowInsetBottom();
            int keyboardInset = bottomInset > dp(120) ? bottomInset : 0;
            imeVisible = keyboardInset > 0;
            view.setPadding(0, 0, 0, heldKeyboardInset(keyboardInset));
        }
        if (webView instanceof RdpInputWebView) {
            ((RdpInputWebView) webView).setImeVisible(imeVisible);
        }
        onImeVisibilityChanged(imeVisible);
    }

    /**
     * While the keyboard is locked open and something closes it (a tap on a
     * remote desktop canvas, for instance), keeps its height as padding until
     * it is back, so the page and a remote desktop are not resized meanwhile.
     */
    private int heldKeyboardInset(int imeBottom) {
        if (imeBottom > 0) {
            heldImeBottom = imeBottom;
            imeHoldStartedAt = 0L;
            return imeBottom;
        }
        if (keyboardLock != KEYBOARD_LOCKED_OPEN || heldImeBottom <= 0 || !hasWindowFocus()) {
            heldImeBottom = 0;
            return 0;
        }
        long now = SystemClock.elapsedRealtime();
        if (imeHoldStartedAt == 0L) {
            imeHoldStartedAt = now;
        } else if (now - imeHoldStartedAt > KEYBOARD_HOLD_TIMEOUT_MS) {
            heldImeBottom = 0;
            return 0;
        }
        return heldImeBottom;
    }

    private final Runnable reshowLockedKeyboard = () -> {
        if (keyboardLock != KEYBOARD_LOCKED_OPEN || imeShown || !hasWindowFocus()) {
            return;
        }
        if (webView instanceof RdpInputWebView
            && ((RdpInputWebView) webView).isForcedImeEnabled()) {
            // Remote desktop typing: reopen the keyboard on the same connection.
            ((RdpInputWebView) webView).reshowForcedIme();
            return;
        }
        forceShowKeyboard(true);
    };

    private final Runnable releaseKeyboardHold = () -> {
        if (imeShown || heldImeBottom <= 0) {
            return;
        }
        heldImeBottom = 0;
        if (rootContainer != null) {
            rootContainer.requestApplyInsets();
        }
    };

    private void onImeVisibilityChanged(boolean visible) {
        boolean wasShown = imeShown;
        imeShown = visible;
        if (wasShown && !visible && keyboardLock == KEYBOARD_LOCKED_OPEN && hasWindowFocus()) {
            addressBarHandler.removeCallbacks(reshowLockedKeyboard);
            addressBarHandler.postDelayed(reshowLockedKeyboard, KEYBOARD_RESHOW_DELAY_MS);
            addressBarHandler.removeCallbacks(releaseKeyboardHold);
            addressBarHandler.postDelayed(releaseKeyboardHold, KEYBOARD_HOLD_TIMEOUT_MS + 100L);
        }
    }

    /**
     * Locks the keyboard in its current state: open stays open (it is brought
     * back whenever it closes), hidden stays hidden (pages cannot raise it).
     * Tapping again unlocks.
     */
    private void toggleKeyboardLock() {
        String message;
        if (keyboardLock != KEYBOARD_UNLOCKED) {
            keyboardLock = KEYBOARD_UNLOCKED;
            message = "Keyboard unlocked";
        } else if (imeShown) {
            keyboardLock = KEYBOARD_LOCKED_OPEN;
            message = "Keyboard locked open";
        } else {
            keyboardLock = KEYBOARD_LOCKED_HIDDEN;
            message = "Keyboard locked hidden";
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        if (activeSessionKey != null) {
            preferences.edit()
                .putInt(projectStateKey(KEYBOARD_LOCK_KEY, activeSessionKey), keyboardLock)
                .apply();
        }
        heldImeBottom = keyboardLock == KEYBOARD_LOCKED_OPEN ? heldImeBottom : 0;
        if (keyboardLock == KEYBOARD_LOCKED_HIDDEN) {
            hideSystemKeyboard();
        }
        if (webView instanceof RdpInputWebView) {
            // Re-evaluate whether the WebView accepts text input.
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.restartInput(webView);
            }
        }
        for (ProjectSession session : projectSessions.values()) {
            syncMouseMode(session.webView);
        }
        if (webView != null && activeSessionKey == null) {
            syncMouseMode(webView);
        }
        if (rootContainer != null) {
            rootContainer.requestApplyInsets();
        }
        updateKeyboardLockButton();
    }

    private void updateKeyboardLockButton() {
        if (keyboardLockButton == null) {
            return;
        }
        String label;
        String description;
        if (keyboardLock == KEYBOARD_LOCKED_OPEN) {
            label = "⌨🔒";
            description = "Keyboard locked open. Tap to unlock.";
        } else if (keyboardLock == KEYBOARD_LOCKED_HIDDEN) {
            label = "🚫⌨";
            description = "Keyboard locked hidden. Tap to unlock.";
        } else {
            label = "⌨🔓";
            description = "Lock the keyboard open or hidden";
        }
        keyboardLockButton.setText(label);
        keyboardLockButton.setContentDescription(description);
        keyboardLockButton.setBackgroundTintList(ColorStateList.valueOf(
            keyboardLock == KEYBOARD_UNLOCKED ? KEY_BACKGROUND : ACCENT
        ));
        keyboardLockButton.setTextColor(
            keyboardLock == KEYBOARD_UNLOCKED ? Color.BLACK : Color.WHITE
        );
    }

    private void applyAddressBarTopInset(int topInset) {
        if (addressBar == null) {
            return;
        }
        addressBar.setPadding(dp(6), dp(4) + topInset, dp(6), dp(4));
        ViewGroup.LayoutParams params = addressBar.getLayoutParams();
        if (params != null && params.height != dp(ADDRESS_BAR_HEIGHT_DP) + topInset) {
            params.height = dp(ADDRESS_BAR_HEIGHT_DP) + topInset;
            addressBar.setLayoutParams(params);
            updateAddressBarOverlay();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(90);
        target.setInitialScale(0);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(target, true);

        target.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(
                WebView view,
                boolean isDialog,
                boolean isUserGesture,
                Message resultMsg
            ) {
                // Links opened with window.open (e.g. terminal and editor links) go to
                // the system browser instead of replacing the code-server page. A new
                // window on the same site (code-server's New Window, a folder opened
                // in a new window) becomes a new project session in the app.
                String openerUrl = view.getUrl();
                boolean openerIsRemoteDesktop = rdpWebViews.contains(view);
                WebView popup = new WebView(view.getContext());
                popup.setWebViewClient(new WebViewClient() {
                    private boolean handled;

                    private void openOnce(WebView popupView, Uri uri) {
                        if (handled) {
                            return;
                        }
                        handled = true;
                        if (!openerIsRemoteDesktop && sameSite(openerUrl, uri)) {
                            String address = uri.toString();
                            // The popup is never attached to a window, so its own
                            // post() queue would never run: use the main handler.
                            addressBarHandler.post(() -> openNewWindowAsProject(address));
                        } else {
                            openExternalUrl(uri);
                        }
                        addressBarHandler.post(() -> {
                            popupView.stopLoading();
                            popupView.destroy();
                        });
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(
                        WebView popupView,
                        WebResourceRequest request
                    ) {
                        openOnce(popupView, request.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView popupView, String url, Bitmap favicon) {
                        if (url != null && !url.startsWith("about:")) {
                            openOnce(popupView, Uri.parse(url));
                        }
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
        target.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Remote desktop WebViews hold credentials in their JavaScript
                // bridge, so they never leave the loopback gateway page.
                if (rdpWebViews.contains(view)) {
                    return !isGatewayUrl(request.getUrl().toString());
                }
                return request.isForMainFrame()
                    && reauthorizeInsteadOfAccessLogin(view, request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                String lastFinishedUrl = lastFinishedUrls.get(view);
                if (lastFinishedUrl == null || !lastFinishedUrl.equals(url)) {
                    appliedLayoutZoomSteps.remove(view);
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateAddressFromWebView(view, url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                installKeyboardBridge(view, false);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                lastFinishedUrls.put(view, url);
                updateAddressFromWebView(view, url);
                installKeyboardBridge(view, true);
                if (view == webView) {
                    showAddressBarTemporarily();
                }
            }

        });
    }

    private WebView createProjectWebView() {
        WebView target = new RdpInputWebView(this);
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
        target.setVisibility(View.GONE);
        configureWebView(target);
        applyWebViewRendererPriority(target);
        webContainer.addView(
            target,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );
        return target;
    }

    private void showBlankWebView() {
        webView = createProjectWebView();
        webView.setVisibility(View.VISIBLE);
        webView.onResume();
        activeSessionKey = null;
        showZoomOf(null);
        restoreInputModes(null);
    }

    /** Per-project preference key; pages outside a project use the global one. */
    private static String projectStateKey(String base, String sessionKey) {
        return sessionKey == null ? base : base + ":" + sessionKey;
    }

    private boolean mouseModeFor(String sessionKey) {
        boolean global = preferences.getBoolean(MOUSE_MODE_KEY, false);
        return sessionKey == null
            ? global
            : preferences.getBoolean(projectStateKey(MOUSE_MODE_KEY, sessionKey), global);
    }

    private int keyboardLockFor(String sessionKey) {
        return sessionKey == null
            ? KEYBOARD_UNLOCKED
            : preferences.getInt(projectStateKey(KEYBOARD_LOCK_KEY, sessionKey), KEYBOARD_UNLOCKED);
    }

    private String sessionKeyOf(WebView target) {
        if (target == webView) {
            return activeSessionKey;
        }
        for (Map.Entry<String, ProjectSession> entry : projectSessions.entrySet()) {
            if (entry.getValue().webView == target) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Applies the mouse mode and keyboard lock saved for the project in front. */
    private void restoreInputModes(String sessionKey) {
        mouseModeEnabled = mouseModeFor(sessionKey);
        keyboardLock = keyboardLockFor(sessionKey);
        heldImeBottom = 0;
        updateKeyboardLockButton();
        applyMouseMode();
        if (webView instanceof RdpInputWebView) {
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.restartInput(webView);
            }
        }
        if (keyboardLock == KEYBOARD_LOCKED_HIDDEN) {
            hideSystemKeyboard();
        } else if (keyboardLock == KEYBOARD_LOCKED_OPEN && !imeShown) {
            addressBarHandler.removeCallbacks(reshowLockedKeyboard);
            addressBarHandler.postDelayed(reshowLockedKeyboard, 500L);
        }
    }

    /** Makes the zoom slider show (and set) the zoom of the page in front. */
    private void showZoomOf(String sessionKey) {
        layoutZoomSteps = zoomStepsFor(sessionKey);
        if (zoomSlider != null) {
            zoomSlider.setProgress(layoutZoomSteps - MIN_LAYOUT_ZOOM_STEPS);
        }
        updateZoomPercentLabel(layoutZoomSteps);
    }

    private LinearLayout createZoomOverlay() {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(14), dp(4), dp(12), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(190, 243, 243, 243));
        background.setCornerRadius(dp(20));
        background.setStroke(Math.max(1, dp(1) / 2), Color.argb(40, 0, 0, 0));
        overlay.setBackground(background);
        overlay.setElevation(dp(2));

        TextView smaller = new TextView(this);
        smaller.setText("A");
        smaller.setTextSize(11);
        smaller.setTextColor(Color.argb(170, 0, 0, 0));
        smaller.setTypeface(Typeface.DEFAULT_BOLD);
        overlay.addView(smaller);

        SeekBar slider = new SeekBar(this);
        zoomSlider = slider;
        slider.setMax(MAX_LAYOUT_ZOOM_STEPS - MIN_LAYOUT_ZOOM_STEPS);
        slider.setProgress(layoutZoomSteps - MIN_LAYOUT_ZOOM_STEPS);
        slider.setContentDescription("UI zoom");
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(90, 0, 0, 0)));
        slider.setPadding(dp(12), 0, dp(12), 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int steps = progress + MIN_LAYOUT_ZOOM_STEPS;
                updateZoomPercentLabel(steps);
                if (fromUser && !zoomSliderTracking) {
                    // Keyboard or accessibility adjustments apply immediately.
                    setLayoutZoomSteps(steps);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                zoomSliderTracking = true;
                addressBarHandler.removeCallbacks(autoHideAddressBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                zoomSliderTracking = false;
                // Relayout once on release instead of on every step while dragging.
                setLayoutZoomSteps(seekBar.getProgress() + MIN_LAYOUT_ZOOM_STEPS);
                scheduleAddressBarAutoHide();
            }
        });
        overlay.addView(slider, new LinearLayout.LayoutParams(dp(180), dp(36)));

        TextView larger = new TextView(this);
        larger.setText("A");
        larger.setTextSize(17);
        larger.setTextColor(Color.argb(170, 0, 0, 0));
        larger.setTypeface(Typeface.DEFAULT_BOLD);
        overlay.addView(larger);

        zoomPercentLabel = new TextView(this);
        zoomPercentLabel.setTextSize(12);
        zoomPercentLabel.setTextColor(Color.BLACK);
        zoomPercentLabel.setTypeface(Typeface.MONOSPACE);
        zoomPercentLabel.setGravity(Gravity.END);
        zoomPercentLabel.setMinWidth(dp(44));
        overlay.addView(zoomPercentLabel);
        updateZoomPercentLabel(layoutZoomSteps);
        return overlay;
    }

    private void updateZoomPercentLabel(int steps) {
        if (zoomPercentLabel == null) {
            return;
        }
        int zoomPercent = (int) Math.round(Math.pow(LAYOUT_ZOOM_FACTOR, steps) * 100.0);
        zoomPercentLabel.setText(zoomPercent + "%");
    }

    private void setLayoutZoomSteps(int steps) {
        int nextSteps = clampZoomSteps(steps);
        if (webView == null || nextSteps == layoutZoomSteps) {
            return;
        }
        layoutZoomSteps = nextSteps;
        preferences.edit().putInt(zoomStepsKey(activeSessionKey), layoutZoomSteps).apply();
        applyLayoutZoom(webView, true);
    }

    private static int clampZoomSteps(int steps) {
        return Math.max(MIN_LAYOUT_ZOOM_STEPS, Math.min(MAX_LAYOUT_ZOOM_STEPS, steps));
    }

    /** Each project keeps its own zoom; pages outside a project use the global one. */
    private static String zoomStepsKey(String sessionKey) {
        return sessionKey == null ? LAYOUT_ZOOM_STEPS_KEY : LAYOUT_ZOOM_STEPS_KEY + ":" + sessionKey;
    }

    /** A project's zoom; projects without one start from the global zoom. */
    private int zoomStepsFor(String sessionKey) {
        int global = preferences.getInt(LAYOUT_ZOOM_STEPS_KEY, 0);
        return clampZoomSteps(sessionKey == null
            ? global
            : preferences.getInt(zoomStepsKey(sessionKey), global));
    }

    private int zoomStepsFor(WebView target) {
        if (target == webView) {
            return layoutZoomSteps;
        }
        for (Map.Entry<String, ProjectSession> entry : projectSessions.entrySet()) {
            if (entry.getValue().webView == target) {
                return zoomStepsFor(entry.getKey());
            }
        }
        return zoomStepsFor((String) null);
    }

    private static int calculateLayoutViewportWidth(int steps) {
        return (int) Math.round(DESKTOP_VIEWPORT_WIDTH / Math.pow(LAYOUT_ZOOM_FACTOR, steps));
    }

    /**
     * Applies the layout zoom in place by changing the virtual viewport width and
     * pinning the page scale to fit it. The page reloads only when the web view
     * still does not fit afterwards and a fallback reload is allowed.
     */
    private void applyLayoutZoom(WebView target, boolean allowFallbackReload) {
        if (target == null) {
            return;
        }
        int requestedSteps = zoomStepsFor(target);
        int viewportWidth = calculateLayoutViewportWidth(requestedSteps);
        int viewWidthPx = target.getWidth() > 0
            ? target.getWidth()
            : (webContainer == null ? 0 : webContainer.getWidth());
        float fitWidthDp = viewWidthPx / getResources().getDisplayMetrics().density;
        String script = "(() => {"
            + "const width=" + viewportWidth + ";"
            + "if(window.__codeServerAppSetViewportWidth){"
            + "return window.__codeServerAppSetViewportWidth(width,"
            + String.format(Locale.US, "%.2f", fitWidthDp) + ","
            + allowFallbackReload + ");}"
            + "let viewport=document.querySelector('meta[name=viewport]');"
            + "if(!viewport){viewport=document.createElement('meta');"
            + "viewport.name='viewport';"
            + "(document.head||document.documentElement).appendChild(viewport);}"
            + "viewport.content='width='+width+','"
            + "+' minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes';"
            + "document.documentElement.style.zoom='1';"
            + "if(document.body){document.body.style.zoom='1';"
            + "document.body.style.width='';document.body.style.minWidth='';}"
            + "window.__codeServerAppViewportWidth=width;"
            + "window.dispatchEvent(new Event('resize'));"
            + "return width;"
            + "})()";
        target.evaluateJavascript(script, value -> {
            if (requestedSteps != zoomStepsFor(target)) {
                return;
            }
            appliedLayoutZoomSteps.put(target, requestedSteps);
            target.requestLayout();
            target.invalidate();
        });
    }

    private void forceShowKeyboard() {
        forceShowKeyboard(false);
    }

    private void forceShowKeyboard(boolean quiet) {
        if (webView == null) {
            return;
        }
        WebView target = webView;
        target.requestFocus();
        String script = "window.__codeServerAppForceKeyboard"
            + " ? window.__codeServerAppForceKeyboard() : false";
        target.evaluateJavascript(script, value -> target.postDelayed(() -> {
            if (target != webView) {
                return;
            }
            if (!quiet) {
                Toast.makeText(
                    this,
                    "\"ironrdp\"".equals(value)
                        ? "IronRDP focused"
                        : "RDP canvas not found",
                    Toast.LENGTH_SHORT
                ).show();
            }
            if (target instanceof RdpInputWebView) {
                ((RdpInputWebView) target).showForcedIme(
                    "\"ironrdp\"".equals(value)
                );
            }
        }, 100L));
    }

    private void switchToProjectUrl(String address) {
        String normalized = normalizeAddress(address);
        if (normalized.isEmpty()) {
            return;
        }
        if (RdpConnectionPanel.isRdpAddress(normalized)) {
            addressField.clearFocus();
            String host = RdpConnectionPanel.hostOf(normalized);
            String username = AccessTokenStore.username(this, host);
            String password = AccessTokenStore.loadPassword(this, host);
            boolean ready = findProjectSession(normalized) != null
                || (AccessTokenStore.credential(this, host) != null
                    && !username.isEmpty()
                    && password != null
                    && !password.isEmpty());
            if (ready) {
                openRdpSession(normalized, username, password, false);
            } else {
                // Sign-in or credentials are missing: the panel collects them.
                rdpPanel.show(normalized);
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();
        cleanupExpiredProjectSessions(now);

        ProjectSession targetSession = findProjectSession(normalized);
        boolean created = targetSession == null;
        if (created) {
            targetSession = new ProjectSession(createProjectWebView());
            projectSessions.put(normalized, targetSession);
        }

        String currentUrl = targetSession.webView.getUrl();
        boolean restoreSavedAddress = !created
            && !addressesEquivalent(currentUrl, normalized);

        activateProjectSession(normalized, targetSession, now);
        if (created || restoreSavedAddress) {
            loadProjectUrl(targetSession.webView, normalized);
        }
        evictExcessProjectSessions();

        String displayedAddress = created || restoreSavedAddress
            ? normalized
            : currentUrl;
        addressField.setText(displayedAddress);
        preferences.edit().putString(ADDRESS_KEY, displayedAddress).apply();
        addressField.clearFocus();
        targetSession.webView.requestFocus();
        showAddressBarTemporarily();
    }

    /**
     * Opens (or returns to) a built-in remote desktop session. The IronRDP web
     * client runs in a WebView restricted to the loopback {@link RdpGateway},
     * so it shares the mouse mode, key bar, zoom and address bar with web
     * projects. {@code reconnect} reloads an existing session with the given
     * credentials.
     */
    private void openRdpSession(
        String address,
        String username,
        String password,
        boolean reconnect
    ) {
        String normalized = RdpConnectionPanel.normalize(address);
        String host = RdpConnectionPanel.hostOf(normalized);
        RdpGateway gateway;
        try {
            gateway = RdpGateway.get(this);
        } catch (IOException exception) {
            Toast.makeText(this, "Could not start the remote desktop gateway", Toast.LENGTH_LONG)
                .show();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        cleanupExpiredProjectSessions(now);
        ProjectSession session = findProjectSession(normalized);
        boolean created = session == null;
        if (created) {
            WebView view = createProjectWebView();
            view.addJavascriptInterface(rdpPageBridge, "YourWorkspaceRdp");
            rdpWebViews.add(view);
            applyWebViewRendererPriority(view);
            applyKeepAliveMode();
            session = new ProjectSession(view);
            projectSessions.put(normalized, session);
        }
        activateProjectSession(normalized, session, now);
        if (created || reconnect) {
            String user = username == null ? "" : username.trim();
            String domain = "";
            int separator = user.indexOf('\\');
            if (separator > 0) {
                domain = user.substring(0, separator);
                user = user.substring(separator + 1);
            }
            String gatewayToken = gateway.newSession(host);
            rdpPageBridge.register(
                gatewayToken,
                new RdpPageBridge.Session(normalized, host, user, domain, password),
                gateway
            );
            session.webView.loadUrl(gateway.pageUrl(gatewayToken));
        }
        evictExcessProjectSessions();

        addressField.setText(normalized);
        preferences.edit().putString(ADDRESS_KEY, normalized).apply();
        addressField.clearFocus();
        session.webView.requestFocus();
        showAddressBarTemporarily();
    }

    private void onRdpSessionEvent(RdpPageBridge.Session session, String event, String detail) {
        switch (event) {
        case "credentials_rejected":
            AccessTokenStore.clearPassword(this, session.host);
            rdpPanel.show(session.address, "Windows rejected the user name or password.");
            break;
        case "sign_in_failed":
            rdpPanel.show(
                session.address,
                "The remote PC ended the connection during Windows sign-in. Check the user name "
                    + "and the account password (not the PIN); for a Microsoft account use its "
                    + "email address." + (detail.isEmpty() ? "" : "\n\n" + detail)
            );
            break;
        case "login_required":
            rdpPanel.show(session.address, "Cloudflare sign-in expired. Sign in again to reconnect.");
            break;
        case "file_saved":
            Toast.makeText(this, "Saved to " + detail, Toast.LENGTH_SHORT).show();
            break;
        case "failed":
            Toast.makeText(
                this,
                "Remote desktop disconnected" + (detail.isEmpty() ? "" : ": " + detail),
                Toast.LENGTH_LONG
            ).show();
            break;
        default:
            break;
        }
    }

    private final Map<WebView, Long> accessReauthorizedAt = new WeakHashMap<>();

    /**
     * Loads a web project. With a Cloudflare Access service token chosen for it,
     * the app first authenticates natively and stores the CF_Authorization
     * cookie Access returns, so the page and its WebSockets are authorized; the
     * secret itself is not sent through the page unless no cookie came back.
     */
    private void loadProjectUrl(WebView target, String url) {
        ServiceTokenStore.ServiceToken token = ServiceTokenStore.forProject(this, url);
        if (token == null || RdpConnectionPanel.isRdpAddress(url)) {
            target.loadUrl(url);
            return;
        }
        new Thread(() -> {
            ServiceTokenAuth.Result result = ServiceTokenAuth.authorize(url, token);
            runOnUiThread(() -> {
                if (!isProjectWebViewAlive(target)) {
                    return;
                }
                CookieManager cookies = CookieManager.getInstance();
                for (String cookie : result.cookies) {
                    cookies.setCookie(url, cookie);
                }
                cookies.flush();
                if (result.rejected) {
                    Toast.makeText(
                        this,
                        "Cloudflare Access rejected the service token “" + token.name + "”",
                        Toast.LENGTH_LONG
                    ).show();
                }
                if (result.cookies.isEmpty()) {
                    target.loadUrl(url, AccessCredential.service(token).headers());
                } else {
                    target.loadUrl(url);
                }
            });
        }, "AccessServiceToken").start();
    }

    private boolean isProjectWebViewAlive(WebView target) {
        if (target == webView) {
            return true;
        }
        for (ProjectSession session : projectSessions.values()) {
            if (session.webView == target) {
                return true;
            }
        }
        return false;
    }

    /**
     * When a project with a service token is sent to the Cloudflare Access
     * login (its session expired), authenticates again and reloads instead.
     * At most once per 30 s per page, so a rejected token shows the login.
     */
    private boolean reauthorizeInsteadOfAccessLogin(WebView view, Uri url) {
        if (!ServiceTokenAuth.isAccessLogin(url)) {
            return false;
        }
        String sessionKey = sessionKeyOf(view);
        if (sessionKey == null || ServiceTokenStore.forProject(this, sessionKey) == null) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        Long last = accessReauthorizedAt.get(view);
        if (last != null && now - last < 30_000L) {
            return false;
        }
        accessReauthorizedAt.put(view, now);
        String current = view.getUrl();
        String destination = current != null && !ServiceTokenAuth.isAccessLogin(Uri.parse(current))
            ? current
            : sessionKey;
        loadProjectUrl(view, destination);
        return true;
    }

    private boolean isGatewayUrl(String url) {
        return url != null && url.startsWith("http://127.0.0.1:");
    }

    private ProjectSession findProjectSession(String address) {
        return projectSessions.get(normalizeAddress(address));
    }

    private void updateAddressFromWebView(WebView source, String url) {
        if (source != webView || url == null || rdpWebViews.contains(source)) {
            // Remote desktop sessions keep showing their rdp:// address.
            return;
        }
        String normalized = normalizeAddress(url);
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return;
        }
        if (addressField != null && !addressField.hasFocus()) {
            addressField.setText(normalized);
        }
        preferences.edit().putString(ADDRESS_KEY, normalized).apply();
    }

    private void activateProjectSession(
        String sessionKey,
        ProjectSession targetSession,
        long now
    ) {
        if (webView == targetSession.webView) {
            activeSessionKey = sessionKey;
            targetSession.webView.requestFocus();
            return;
        }

        if (webView != null) {
            if (activeSessionKey == null) {
                destroyWebView(webView);
            } else {
                ProjectSession currentSession = projectSessions.get(activeSessionKey);
                if (currentSession != null) {
                    currentSession.lastInactiveAt = now;
                }
                // Keep hot sessions attached, visible behind the active WebView, and
                // resumed so RDP/WebSocket connections are not suspended on switch.
                webView.clearFocus();
            }
        }

        webView = targetSession.webView;
        activeSessionKey = sessionKey;
        targetSession.lastInactiveAt = 0L;
        showZoomOf(sessionKey);
        restoreInputModes(sessionKey);
        webView.setVisibility(View.VISIBLE);
        webView.bringToFront();
        webView.onResume();
        Integer appliedSteps = appliedLayoutZoomSteps.get(webView);
        boolean zoomChanged = appliedSteps != null && appliedSteps != layoutZoomSteps;
        applyLayoutZoom(webView, zoomChanged);
        syncModifiers(webView);
        syncMouseMode(webView);
        updateAddressBarOverlay();
    }

    private void cleanupExpiredProjectSessions(long now) {
        if (keepAliveEnabled) {
            return;
        }
        Iterator<Map.Entry<String, ProjectSession>> iterator =
            projectSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ProjectSession> entry = iterator.next();
            ProjectSession session = entry.getValue();
            if (entry.getKey().equals(activeSessionKey) || rdpWebViews.contains(session.webView)) {
                // Remote desktops stay until they are disconnected.
                continue;
            }
            if (session.lastInactiveAt > 0L
                && now - session.lastInactiveAt >= PROJECT_SESSION_TTL_MS) {
                iterator.remove();
                destroyWebView(session.webView);
            }
        }
    }

    private void evictExcessProjectSessions() {
        while (projectSessions.size() > MAX_HOT_PROJECT_SESSIONS) {
            String oldestKey = null;
            long oldestInactiveAt = Long.MAX_VALUE;
            for (Map.Entry<String, ProjectSession> entry : projectSessions.entrySet()) {
                if (entry.getKey().equals(activeSessionKey)) {
                    continue;
                }
                if (entry.getValue().lastInactiveAt < oldestInactiveAt) {
                    oldestKey = entry.getKey();
                    oldestInactiveAt = entry.getValue().lastInactiveAt;
                }
            }
            if (oldestKey == null) {
                return;
            }
            ProjectSession removed = projectSessions.remove(oldestKey);
            if (removed != null) {
                destroyWebView(removed.webView);
            }
        }
    }

    private boolean isProjectSessionHot(String address, long now) {
        String normalized = normalizeAddress(address);
        ProjectSession session = findProjectSession(normalized);
        if (session == null) {
            return false;
        }
        return normalized.equals(activeSessionKey)
            || (keepAliveEnabled && session.lastInactiveAt > 0L)
            || (session.lastInactiveAt > 0L
                && now - session.lastInactiveAt < PROJECT_SESSION_TTL_MS);
    }

    /**
     * Ends the remote desktop session in front: its page is destroyed, which
     * closes the gateway connection and the Cloudflare tunnel. The connection
     * panel then offers to reconnect.
     */
    private void disconnectActiveRdpSession() {
        WebView target = webView;
        String address = activeSessionKey;
        if (target == null || address == null || !rdpWebViews.contains(target)) {
            return;
        }
        projectSessions.remove(address);
        rdpWebViews.remove(target);
        showBlankWebView();
        destroyWebView(target);
        applyKeepAliveMode();
        updateAddressBarOverlay();
        rdpPanel.show(address, "Disconnected from the remote desktop.");
    }

    private void destroyWebView(WebView target) {
        if (rdpWebViews.remove(target)) {
            applyKeepAliveMode();
        }
        appliedLayoutZoomSteps.remove(target);
        lastFinishedUrls.remove(target);
        if (webContainer != null) {
            webContainer.removeView(target);
        }
        target.stopLoading();
        target.destroy();
    }

    private void loadEnteredAddress() {
        String normalized = normalizeAddress(addressField.getText().toString());
        if (normalized.isEmpty()) {
            return;
        }

        switchToProjectUrl(normalized);
    }

    /** Shows the address bar and hides it again after five seconds. */
    private void showAddressBarTemporarily() {
        if (addressBar == null) {
            return;
        }
        addressBar.setVisibility(View.VISIBLE);
        if (zoomOverlay != null) {
            zoomOverlay.setVisibility(View.VISIBLE);
        }
        updateAddressBarOverlay();
        scheduleAddressBarAutoHide();
    }

    private void scheduleAddressBarAutoHide() {
        addressBarHandler.removeCallbacks(autoHideAddressBar);
        addressBarHandler.postDelayed(autoHideAddressBar, ADDRESS_BAR_AUTO_HIDE_MS);
    }

    private void hideAddressBar() {
        addressBarHandler.removeCallbacks(autoHideAddressBar);
        if (addressBar != null) {
            addressBar.setVisibility(View.GONE);
        }
        if (zoomOverlay != null) {
            zoomOverlay.setVisibility(View.GONE);
        }
        updateAddressBarOverlay();
    }

    /**
     * Over a remote desktop the address bar floats above the page instead of
     * pushing it down, so showing and auto-hiding it does not resize the
     * remote desktop twice. Web pages keep the regular layout.
     */
    private void updateAddressBarOverlay() {
        if (disconnectButton != null) {
            boolean rdpActive = webView != null && rdpWebViews.contains(webView);
            disconnectButton.setVisibility(rdpActive ? View.VISIBLE : View.GONE);
            uploadButton.setVisibility(rdpActive ? View.VISIBLE : View.GONE);
        }
        if (contentFrame == null || addressBar == null) {
            return;
        }
        boolean overlay = fullscreenEnabled
            && addressBar.getVisibility() == View.VISIBLE
            && webView != null
            && rdpWebViews.contains(webView);
        ViewGroup.LayoutParams barParams = addressBar.getLayoutParams();
        int barHeight = barParams != null && barParams.height > 0 ? barParams.height : dp(ADDRESS_BAR_HEIGHT_DP);
        LinearLayout.LayoutParams frameParams =
            (LinearLayout.LayoutParams) contentFrame.getLayoutParams();
        int frameMargin = overlay ? -barHeight : 0;
        if (frameParams != null && frameParams.topMargin != frameMargin) {
            frameParams.topMargin = frameMargin;
            contentFrame.setLayoutParams(frameParams);
        }
        if (zoomOverlay != null
            && zoomOverlay.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams zoomParams =
                (FrameLayout.LayoutParams) zoomOverlay.getLayoutParams();
            int zoomMargin = dp(8) + (overlay ? barHeight : 0);
            if (zoomParams.topMargin != zoomMargin) {
                zoomParams.topMargin = zoomMargin;
                zoomOverlay.setLayoutParams(zoomParams);
            }
        }
    }

    private void loadProjects() {
        projects.clear();
        String serialized = preferences.getString(PROJECTS_KEY, "[]");
        try {
            JSONArray array = new JSONArray(serialized == null ? "[]" : serialized);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String name = item.optString("name", "").trim();
                String url = item.optString("url", "").trim();
                if (!name.isEmpty() && !url.isEmpty()) {
                    projects.add(new ProjectProfile(name, url));
                }
            }
        } catch (Exception ignored) {
            projects.clear();
        }
    }

    private void persistProjects() {
        JSONArray array = new JSONArray();
        for (ProjectProfile project : projects) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", project.name);
                item.put("url", project.url);
                array.put(item);
            } catch (Exception ignored) {}
        }
        preferences.edit().putString(PROJECTS_KEY, array.toString()).apply();
    }

    /** Saved projects as cards: tap to open, ✎ to edit; new and save-current on top. */
    private void showProjectSwitcher() {
        long now = SystemClock.elapsedRealtime();
        cleanupExpiredProjectSessions(now);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Projects");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(28, 28, 30));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button saveCurrent = pillButton("Save current", false);
        Button addNew = pillButton("＋ New", true);
        header.addView(saveCurrent);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        addParams.setMarginStart(dp(8));
        header.addView(addNew, addParams);
        content.addView(header);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("Close", null)
            .create();

        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No saved projects yet. Add one, or save the current address.");
            empty.setTextSize(14);
            empty.setTextColor(Color.rgb(110, 110, 115));
            empty.setPadding(dp(4), dp(8), dp(4), dp(12));
            list.addView(empty);
        }
        for (ProjectProfile project : projects) {
            View card = projectCard(
                project,
                isProjectSessionHot(project.url, now),
                () -> {
                    dialog.dismiss();
                    openProject(project);
                },
                () -> {
                    dialog.dismiss();
                    showProjectEditor(projects.indexOf(project), null);
                }
            );
            card.setTag(project);
            list.addView(card);
        }

        saveCurrent.setOnClickListener(view -> {
            String url = normalizeAddress(addressField.getText().toString());
            if (url.isEmpty()) {
                Toast.makeText(this, "Enter an address first", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            showProjectEditor(-1, url);
        });
        addNew.setOnClickListener(view -> {
            dialog.dismiss();
            showProjectEditor(-1, "");
        });
        dialog.show();
    }

    private View projectCard(
        ProjectProfile project,
        boolean hot,
        Runnable open,
        Runnable edit
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(4), dp(10), dp(6), dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.rgb(245, 245, 248));
        shape.setCornerRadius(dp(12));
        card.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Color.argb(30, 0, 0, 0)),
            shape,
            null
        ));
        card.setOnClickListener(view -> open.run());

        TextView handle = new TextView(this);
        handle.setText("≡");
        handle.setTextSize(20);
        handle.setTextColor(Color.rgb(150, 150, 155));
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription("Drag to reorder " + project.name);
        handle.setOnTouchListener(this::dragProjectCard);
        card.addView(handle, new LinearLayout.LayoutParams(dp(28), dp(40)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(project.name);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Color.rgb(28, 28, 30));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        TextView address = new TextView(this);
        address.setText(project.url);
        address.setTextSize(12);
        address.setTextColor(Color.rgb(110, 110, 115));
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        text.addView(address);

        List<String> tags = new ArrayList<>();
        if (RdpConnectionPanel.isRdpAddress(project.url)) {
            tags.add("RDP");
        }
        ServiceTokenStore.ServiceToken token = ServiceTokenStore.forProject(this, project.url);
        if (token != null) {
            tags.add("🔑 " + token.name);
        }
        if (hot) {
            tags.add("● Open");
        }
        if (!tags.isEmpty()) {
            TextView tagView = new TextView(this);
            tagView.setText(String.join("  ·  ", tags));
            tagView.setTextSize(11);
            tagView.setTextColor(ACCENT);
            tagView.setPadding(0, dp(3), 0, 0);
            tagView.setSingleLine(true);
            tagView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(tagView);
        }
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button editButton = createToolbarButton("✎");
        editButton.setContentDescription("Edit " + project.name);
        editButton.setOnClickListener(view -> edit.run());
        card.addView(editButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private float projectDragLastY;

    /**
     * Drag handle of a project card: the card follows the finger, neighbours
     * move past it, and the new order is saved when the finger lifts.
     */
    private boolean dragProjectCard(View handle, MotionEvent event) {
        View card = (View) handle.getParent();
        LinearLayout list = (LinearLayout) card.getParent();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                projectDragLastY = event.getRawY();
                card.setElevation(dp(6));
                list.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float offset = card.getTranslationY() + event.getRawY() - projectDragLastY;
                projectDragLastY = event.getRawY();
                int index = list.indexOfChild(card);
                int gap = dp(8);
                if (offset > 0 && index < list.getChildCount() - 1) {
                    View next = list.getChildAt(index + 1);
                    if (offset > (next.getHeight() + gap) / 2f) {
                        // Move the neighbour above instead of the dragged card,
                        // which keeps receiving this touch.
                        list.removeView(next);
                        list.addView(next, index);
                        offset -= next.getHeight() + gap;
                    }
                } else if (offset < 0 && index > 0) {
                    View previous = list.getChildAt(index - 1);
                    if (-offset > (previous.getHeight() + gap) / 2f) {
                        list.removeView(previous);
                        list.addView(previous, index);
                        offset += previous.getHeight() + gap;
                    }
                }
                card.setTranslationY(offset);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                card.animate().translationY(0f).setDuration(120).start();
                card.setElevation(0f);
                list.getParent().requestDisallowInterceptTouchEvent(false);
                List<ProjectProfile> ordered = new ArrayList<>();
                for (int position = 0; position < list.getChildCount(); position++) {
                    Object tag = list.getChildAt(position).getTag();
                    if (tag instanceof ProjectProfile) {
                        ordered.add((ProjectProfile) tag);
                    }
                }
                if (ordered.size() == projects.size() && !ordered.equals(projects)) {
                    projects.clear();
                    projects.addAll(ordered);
                    persistProjects();
                }
                return true;
            default:
                return false;
        }
    }

    private Button pillButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setStateListAnimator(null);
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(primary ? ACCENT : Color.TRANSPARENT);
        if (!primary) {
            shape.setStroke(Math.max(1, dp(1)), ACCENT);
        }
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, 0, 0, 0)),
            shape,
            null
        ));
        return button;
    }

    /**
     * Adds a project ({@code index} < 0, with {@code presetUrl} prefilled, e.g.
     * the current address) or edits one: name, address and Cloudflare Access
     * (browser sign-in or a saved service token).
     */
    private void showProjectEditor(int index, String presetUrl) {
        ProjectProfile existing = index >= 0 ? projects.get(index) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        EditText nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setHint("Name");
        EditText urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setHint("https://… or rdp://host");
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String initialUrl = existing != null ? existing.url : (presetUrl == null ? "" : presetUrl);
        nameField.setText(existing != null
            ? existing.name
            : (initialUrl.isEmpty() ? "" : suggestedProjectName(initialUrl)));
        urlField.setText(initialUrl);
        form.addView(formLabel("Name"));
        form.addView(nameField);
        form.addView(formLabel("Address"));
        form.addView(urlField);

        List<ServiceTokenStore.ServiceToken> tokens = ServiceTokenStore.list(this);
        List<String> choices = new ArrayList<>();
        choices.add("Browser sign-in");
        for (ServiceTokenStore.ServiceToken token : tokens) {
            choices.add("Service token: " + token.name);
        }
        Spinner accessChoice = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            choices
        );
        accessChoice.setAdapter(adapter);
        String currentTokenId = initialUrl.isEmpty()
            ? null
            : ServiceTokenStore.projectTokenId(this, initialUrl);
        for (int position = 0; position < tokens.size(); position++) {
            if (tokens.get(position).id.equals(currentTokenId)) {
                accessChoice.setSelection(position + 1);
            }
        }
        form.addView(formLabel("Cloudflare Access"));
        form.addView(accessChoice);
        if (tokens.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Service tokens are added in Settings → Cloudflare Access service tokens.");
            hint.setTextSize(12);
            hint.setTextColor(Color.rgb(110, 110, 115));
            form.addView(hint);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(boldText(existing != null ? "Edit project" : "New project"))
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null);
        if (existing != null) {
            builder.setNeutralButton("Delete", (dialog, which) -> confirmProjectDeletion(index));
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                String url = normalizeAddress(urlField.getText().toString());
                if (url.isEmpty()) {
                    Toast.makeText(this, "Enter an address", Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = nameField.getText().toString().trim();
                if (name.isEmpty()) {
                    name = suggestedProjectName(url);
                }
                ProjectProfile saved = new ProjectProfile(name, url);
                if (existing != null) {
                    projects.set(index, saved);
                } else {
                    projects.add(saved);
                }
                persistProjects();
                int selected = accessChoice.getSelectedItemPosition();
                ServiceTokenStore.setForProject(
                    this,
                    url,
                    selected <= 0 ? null : tokens.get(selected - 1).id
                );
                dialog.dismiss();
                showProjectSwitcher();
            }));
        dialog.show();
    }

    private TextView formLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(Color.rgb(110, 110, 115));
        label.setPadding(dp(4), dp(10), 0, 0);
        return label;
    }

    /**
     * A readable default name: the folder or workspace code-server opens
     * (?folder=/path, ?workspace=/path.code-workspace), else the host.
     */
    private static String suggestedProjectName(String url) {
        if (RdpConnectionPanel.isRdpAddress(url)) {
            return RdpConnectionPanel.hostOf(url);
        }
        Uri uri = Uri.parse(url);
        for (String parameter : new String[] { "folder", "workspace" }) {
            String path = null;
            try {
                path = uri.getQueryParameter(parameter);
            } catch (UnsupportedOperationException ignored) {
                // Not a hierarchical URI.
            }
            if (path != null && !path.isEmpty()) {
                String trimmed = path.replaceAll("/+$", "");
                String base = trimmed.substring(trimmed.lastIndexOf('/') + 1)
                    .replaceAll("\\.code-workspace$", "");
                if (!base.isEmpty()) {
                    return base;
                }
            }
        }
        String host = uri.getHost();
        return host == null || host.isEmpty() ? url : host;
    }

    private static boolean sameSite(String openerUrl, Uri target) {
        if (openerUrl == null || target == null || target.getHost() == null) {
            return false;
        }
        Uri opener = Uri.parse(openerUrl);
        return target.getHost().equalsIgnoreCase(opener.getHost())
            && String.valueOf(target.getScheme()).equalsIgnoreCase(String.valueOf(opener.getScheme()))
            && target.getPort() == opener.getPort();
    }

    /** Opens a same-site new window as a project session and offers to save it. */
    private void openNewWindowAsProject(String url) {
        String normalized = normalizeAddress(url);
        if (normalized.isEmpty()) {
            return;
        }
        boolean alreadyOpen = findProjectSession(normalized) != null;
        switchToProjectUrl(normalized);
        for (ProjectProfile project : projects) {
            if (addressesEquivalent(project.url, normalized)) {
                if (alreadyOpen) {
                    Toast.makeText(this, project.name + " is already open", Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }
        new AlertDialog.Builder(this)
            .setTitle(boldText("New window"))
            .setMessage("Opened as a new session: " + suggestedProjectName(normalized)
                + "\nSave it as a project?")
            .setPositiveButton("Save…", (dialog, which) -> showProjectEditor(-1, normalized))
            .setNegativeButton("Not now", null)
            .show();
    }

    private void openProject(ProjectProfile project) {
        switchToProjectUrl(project.url);
    }

    private void confirmProjectDeletion(int index) {
        ProjectProfile project = projects.get(index);
        new AlertDialog.Builder(this)
            .setTitle(boldText("Delete " + project.name + "?"))
            .setMessage(project.url)
            .setPositiveButton("Delete", (dialog, which) -> {
                projects.remove(index);
                persistProjects();
                showProjectSwitcher();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static CharSequence boldText(String text) {
        SpannableString styled = new SpannableString(text);
        styled.setSpan(
            new StyleSpan(Typeface.BOLD),
            0,
            text.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return styled;
    }

    private static String normalizeAddress(String address) {
        String trimmed = address == null ? "" : address.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (RdpConnectionPanel.isRdpAddress(trimmed)) {
            return RdpConnectionPanel.normalize(trimmed);
        }
        return "http://" + trimmed;
    }

    private static boolean addressesEquivalent(String first, String second) {
        return comparableAddress(first).equals(comparableAddress(second));
    }

    private static String comparableAddress(String address) {
        String normalized = normalizeAddress(address);
        int queryIndex = normalized.indexOf('?');
        int fragmentIndex = normalized.indexOf('#');
        int suffixIndex;
        if (queryIndex < 0) {
            suffixIndex = fragmentIndex;
        } else if (fragmentIndex < 0) {
            suffixIndex = queryIndex;
        } else {
            suffixIndex = Math.min(queryIndex, fragmentIndex);
        }
        String base = suffixIndex < 0 ? normalized : normalized.substring(0, suffixIndex);
        String suffix = suffixIndex < 0 ? "" : normalized.substring(suffixIndex);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + suffix;
    }

    private void installKeyboardBridge(WebView target, boolean applyZoom) {
        // Seed the zoomed viewport width so the first layout already uses it.
        int steps = zoomStepsFor(target);
        String script = "window.__codeServerAppViewportWidth="
            + calculateLayoutViewportWidth(steps) + ";" + KEYBOARD_BRIDGE;
        target.evaluateJavascript(script, value -> {
            if (applyZoom) {
                Integer appliedSteps = appliedLayoutZoomSteps.get(target);
                boolean zoomChanged = (appliedSteps == null && steps != 0)
                    || (appliedSteps != null && appliedSteps != steps);
                applyLayoutZoom(target, zoomChanged);
            }
            syncModifiers(target);
            syncMouseMode(target);
        });
    }

    private void syncModifiers() {
        if (webView != null) {
            syncModifiers(webView);
        }
    }

    private void syncModifierImeCapture() {
        if (webView instanceof RdpInputWebView) {
            ((RdpInputWebView) webView).syncModifierCapture(
                controlLocked || shiftLocked
            );
        }
    }

    private void syncModifiers(WebView target) {
        String script = "if (window.__codeServerAppKeyboard) { "
            + "window.__codeServerAppKeyboard.setModifiers("
            + controlLocked + "," + shiftLocked + "); }";
        target.evaluateJavascript(script, null);
        if (target == webView) {
            target.requestFocus();
        }
    }

    private void sendKey(String key, String code, int keyCode) {
        String script = String.format(Locale.US, """
            (() => {
              if (window.__codeServerAppKeyboard
                  && typeof window.__codeServerAppKeyboard.sendKey === 'function') {
                return window.__codeServerAppKeyboard.sendKey(%1$s, %2$s, %5$d);
              }
              const target = document.activeElement || document.body;
              if (!target) return false;
              if (typeof target.focus === 'function') target.focus();
              const dispatch = (type) => {
                const event = new KeyboardEvent(type, {
                  key: %1$s,
                  code: %2$s,
                  ctrlKey: %3$s,
                  shiftKey: %4$s,
                  altKey: false,
                  metaKey: false,
                  bubbles: true,
                  cancelable: true,
                  composed: true
                });
                try {
                  Object.defineProperty(event, 'keyCode', { get: () => %5$d });
                  Object.defineProperty(event, 'which', { get: () => %5$d });
                } catch (_) {}
                target.dispatchEvent(event);
              };
              dispatch('keydown');
              dispatch('keyup');
              return true;
            })();
            """,
                JSONObject.quote(key),
                JSONObject.quote(code),
                controlLocked,
                shiftLocked,
                keyCode
            );
        webView.evaluateJavascript(script, null);
        webView.requestFocus();
    }

    private void sendControlC() {
        String script = "window.__codeServerAppKeyboard"
            + " && typeof window.__codeServerAppKeyboard.sendShortcut === 'function'"
            + " ? window.__codeServerAppKeyboard.sendShortcut("
            + "'c','KeyC',67,true,false) : false";
        webView.evaluateJavascript(script, null);
        webView.requestFocus();
    }

    private void setMouseModeEnabled(boolean enabled) {
        if (mouseModeEnabled == enabled) {
            return;
        }
        mouseModeEnabled = enabled;
        preferences.edit().putBoolean(projectStateKey(MOUSE_MODE_KEY, activeSessionKey), enabled)
            .apply();
        applyMouseMode();
        Toast.makeText(
            this,
            enabled
                ? "Mouse mode: finger moves the cursor, tap or L/R to click"
                : "Mouse mode off",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void applyMouseMode() {
        if (mouseModeEnabled) {
            hideSystemKeyboard();
        }
        syncMouseModeAll();
        if (mouseModeButton != null) {
            mouseModeButton.setContentDescription(
                mouseModeEnabled ? "Disable mouse mode" : "Enable mouse mode"
            );
            mouseModeButton.setTextColor(mouseModeEnabled ? Color.WHITE : Color.BLACK);
            mouseModeButton.setBackgroundTintList(
                ColorStateList.valueOf(mouseModeEnabled ? ACCENT : KEY_BACKGROUND)
            );
        }
    }

    private void syncMouseModeAll() {
        boolean activeViewIsCached = activeSessionKey != null;
        for (ProjectSession session : projectSessions.values()) {
            syncMouseMode(session.webView);
        }
        if (!activeViewIsCached) {
            syncMouseMode(webView);
        }
    }

    private void syncMouseMode(WebView target) {
        if (target == null) {
            return;
        }
        int widthPx = target.getWidth() > 0
            ? target.getWidth()
            : (webContainer == null ? 0 : webContainer.getWidth());
        float widthDp = widthPx / getResources().getDisplayMetrics().density;
        int lock = target == webView ? keyboardLock : keyboardLockFor(sessionKeyOf(target));
        String script = String.format(
            Locale.US,
            "window.__codeServerAppKeyboard"
                + " && typeof window.__codeServerAppKeyboard.setMouseMode === 'function'"
                + " ? (window.__codeServerAppKeyboard.setKeyboardLocked?.(%b),"
                + " window.__codeServerAppKeyboard.setKeyboardHeldOpen?.(%b),"
                + " window.__codeServerAppKeyboard.setMouseMode(%b, %.2f)) : false",
            lock == KEYBOARD_LOCKED_HIDDEN,
            lock == KEYBOARD_LOCKED_OPEN,
            target == webView ? mouseModeEnabled : mouseModeFor(sessionKeyOf(target)),
            widthDp
        );
        target.evaluateJavascript(script, null);
    }

    private void hideSystemKeyboard() {
        if (webView instanceof RdpInputWebView) {
            ((RdpInputWebView) webView).disableForcedIme();
        }
        InputMethodManager inputMethodManager =
            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null && webView != null) {
            inputMethodManager.hideSoftInputFromWindow(webView.getWindowToken(), 0);
        }
    }

    private void openExternalUrl(Uri uri) {
        if (uri == null) {
            return;
        }
        String scheme = uri.getScheme() == null
            ? ""
            : uri.getScheme().toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "No app can open " + uri, Toast.LENGTH_SHORT).show();
        }
    }

    private void addKey(
        LinearLayout row,
        String label,
        String key,
        String code,
        int keyCode,
        int width
    ) {
        Button button = createKeyButton(label);
        button.setOnClickListener(view -> sendKey(key, code, keyCode));
        row.addView(button, keyLayoutParams(width));
    }

    private void addRepeatingKey(
        LinearLayout row,
        String label,
        String key,
        String code,
        int keyCode,
        int width
    ) {
        Button button = createKeyButton(label);
        button.setOnClickListener(view -> sendKey(key, code, keyCode));
        final Runnable[] repeatAction = new Runnable[1];
        repeatAction[0] = () -> {
            sendKey(key, code, keyCode);
            button.postDelayed(repeatAction[0], 70L);
        };
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                button.removeCallbacks(repeatAction[0]);
                button.postDelayed(repeatAction[0], 350L);
                break;
            case MotionEvent.ACTION_UP:
                button.removeCallbacks(repeatAction[0]);
                view.performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                button.removeCallbacks(repeatAction[0]);
                break;
            default:
                break;
            }
            return true;
        });
        row.addView(button, keyLayoutParams(width));
    }

    /** A flat icon button for the address bar. */
    private Button createToolbarButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17);
        button.setTextColor(Color.rgb(40, 40, 40));
        button.setAllCaps(false);
        button.setMinWidth(dp(36));
        button.setMinimumWidth(dp(36));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setStateListAnimator(null);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(dp(8));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(8));
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, 0, 0, 0)),
            shape,
            mask
        ));
        return button;
    }

    private Button createKeyButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setTextColor(Color.BLACK);
        // A flat rounded key without the default button's insets keeps the bar low.
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(6));
        background.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, 0, 0, 0)),
            background,
            null
        ));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(ColorStateList.valueOf(KEY_BACKGROUND));
        return button;
    }

    private void updateModifierButtons() {
        styleModifierButton(controlButton, "Ctrl", controlLocked);
        styleModifierButton(shiftButton, "Shift", shiftLocked);
    }

    private void styleModifierButton(Button button, String label, boolean locked) {
        if (button == null) {
            return;
        }
        button.setText(label + (locked ? " 🔒" : " 🔓"));
        button.setContentDescription(label + (locked ? " locked" : " unlocked"));
        button.setTextColor(locked ? Color.WHITE : Color.BLACK);
        button.setBackgroundTintList(ColorStateList.valueOf(locked ? ACCENT : KEY_BACKGROUND));
    }

    private LinearLayout.LayoutParams keyLayoutParams(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(KEY_HEIGHT_DP));
        params.setMarginEnd(dp(4));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        // Persist cookies (e.g. the Cloudflare Access session) right away, so the
        // login survives if the system kills the app while it is in the background.
        CookieManager.getInstance().flush();
        if (!keepAliveEnabled) {
            boolean activeViewIsCached = activeSessionKey != null;
            for (ProjectSession session : projectSessions.values()) {
                // Remote desktops keep running in the background (see
                // applyKeepAliveMode); pausing them would drop the session.
                if (rdpWebViews.contains(session.webView)) {
                    continue;
                }
                session.webView.onPause();
            }
            if (!activeViewIsCached && webView != null) {
                webView.onPause();
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rdpPanel != null) {
            rdpPanel.onResume();
        }
        if (keepAliveEnabled) {
            applyKeepAliveMode();
        }
        updateSessionKeepAlivePulse();
        boolean activeViewIsCached = activeSessionKey != null;
        for (ProjectSession session : projectSessions.values()) {
            session.webView.onResume();
        }
        if (!activeViewIsCached && webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (rdpPanel != null) {
            rdpPanel.dismiss();
        }
        keepAliveHandler.removeCallbacks(sessionKeepAlivePulse);
        addressBarHandler.removeCallbacks(autoHideAddressBar);
        boolean activeViewIsCached = activeSessionKey != null;
        for (ProjectSession session : new ArrayList<>(projectSessions.values())) {
            destroyWebView(session.webView);
        }
        projectSessions.clear();
        if (!activeViewIsCached && webView != null) {
            destroyWebView(webView);
        }
        webView = null;
        super.onDestroy();
    }

    private final class RdpInputWebView extends WebView {
        private final KeyCharacterMap virtualKeyboard =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        private boolean forcedImeEnabled;
        private boolean ironRdpMode;
        private boolean imeVisible;
        private boolean imeInputConfirmed;
        private long forcedImeRequestedAt;

        RdpInputWebView(Context context) {
            super(context);
        }

        void showForcedIme(boolean useIronRdp) {
            forcedImeEnabled = true;
            ironRdpMode = useIronRdp;
            imeInputConfirmed = false;
            forcedImeRequestedAt = SystemClock.elapsedRealtime();
            requestFocus();
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.restartInput(this);
                inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        }

        void setImeVisible(boolean visible) {
            boolean wasVisible = imeVisible;
            imeVisible = visible;
            // A keyboard locked open is brought back right away, so keep the
            // forced input connection (and with it the remote desktop typing).
            if (wasVisible && !visible && forcedImeEnabled
                && keyboardLock != KEYBOARD_LOCKED_OPEN) {
                disableForcedIme();
            }
        }

        boolean isForcedImeEnabled() {
            return forcedImeEnabled || isBuiltInRemoteDesktop();
        }

        private boolean isBuiltInRemoteDesktop() {
            return rdpWebViews.contains(this);
        }

        /** Shows the keyboard again on the existing forced input connection. */
        void reshowForcedIme() {
            forcedImeRequestedAt = SystemClock.elapsedRealtime();
            requestFocus();
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        }

        void syncModifierCapture(boolean enabled) {
            if (!enabled) {
                if (forcedImeEnabled && !ironRdpMode) {
                    disableForcedIme();
                }
                return;
            }
            if (!imeVisible && !forcedImeEnabled) {
                return;
            }
            forcedImeEnabled = true;
            forcedImeRequestedAt = SystemClock.elapsedRealtime();
            requestFocus();
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.restartInput(this);
                inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        }

        private void disableForcedIme() {
            if (!forcedImeEnabled) {
                return;
            }
            forcedImeEnabled = false;
            InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.restartInput(this);
            }
        }

        @Override
        public boolean onCheckIsTextEditor() {
            if (keyboardLock == KEYBOARD_LOCKED_HIDDEN) {
                return false;
            }
            if (forcedImeEnabled || isBuiltInRemoteDesktop()) {
                return true;
            }
            // Mouse mode never lets page focus changes raise the system keyboard.
            return !mouseModeEnabled && super.onCheckIsTextEditor();
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (keyboardLock == KEYBOARD_LOCKED_HIDDEN) {
                return null;
            }
            if (isBuiltInRemoteDesktop()) {
                // The page has nothing else to type into: whichever way the
                // keyboard came up, its input goes to the remote desktop.
                ironRdpMode = true;
            } else if (!forcedImeEnabled) {
                return mouseModeEnabled ? null : super.onCreateInputConnection(outAttrs);
            }
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            outAttrs.initialSelStart = 0;
            outAttrs.initialSelEnd = 0;
            return new ForcedImeInputConnection(this);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && forcedImeEnabled
                && !imeVisible
                && keyboardLock != KEYBOARD_LOCKED_OPEN
                && SystemClock.elapsedRealtime() - forcedImeRequestedAt > 500L) {
                disableForcedIme();
            }
            return super.onTouchEvent(event);
        }

        private void confirmImeInput() {
            if (imeInputConfirmed) {
                return;
            }
            imeInputConfirmed = true;
            Toast.makeText(
                MainActivity.this,
                "IME connected",
                Toast.LENGTH_SHORT
            ).show();
        }

        private void dispatchImeKeyEvent(KeyEvent event) {
            int metaState = event.getMetaState();
            if (controlLocked) {
                metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            }
            if (shiftLocked) {
                metaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            }
            KeyEvent copiedEvent = new KeyEvent(
                event.getDownTime(),
                event.getEventTime(),
                event.getAction(),
                event.getKeyCode(),
                event.getRepeatCount(),
                metaState,
                event.getDeviceId(),
                event.getScanCode(),
                event.getFlags(),
                event.getSource()
            );
            post(() -> {
                confirmImeInput();
                RdpInputWebView.this.dispatchKeyEvent(copiedEvent);
            });
        }

        private void dispatchNativeKey(int keyCode) {
            if (ironRdpMode) {
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    dispatchBridgeKey("Backspace", "Backspace", 8);
                    return;
                }
                if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                    dispatchBridgeKey("Delete", "Delete", 46);
                    return;
                }
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    dispatchBridgeKey("Enter", "Enter", 13);
                    return;
                }
            }
            long now = SystemClock.uptimeMillis();
            dispatchImeKeyEvent(new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0
            ));
            dispatchImeKeyEvent(new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_UP,
                keyCode,
                0
            ));
        }

        private void dispatchBridgeKey(String key, String code, int keyCode) {
            String script = "window.__codeServerAppKeyboard"
                + " ? window.__codeServerAppKeyboard.sendKey("
                + JSONObject.quote(key) + ","
                + JSONObject.quote(code) + ","
                + keyCode + ") : false";
            post(() -> {
                confirmImeInput();
                evaluateJavascript(script, null);
            });
        }

        private void dispatchBridgeText(String text) {
            String script = "window.__codeServerAppKeyboard"
                + " ? window.__codeServerAppKeyboard.sendText("
                + JSONObject.quote(text) + ") : false";
            post(() -> {
                confirmImeInput();
                evaluateJavascript(script, null);
            });
        }

        private void dispatchCommittedText(CharSequence text) {
            if (text == null || text.length() == 0) {
                return;
            }
            String value = text.toString();
            if (ironRdpMode) {
                dispatchBridgeText(value);
                return;
            }
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                KeyEvent[] events = virtualKeyboard.getEvents(character.toCharArray());
                if (events != null && events.length > 0) {
                    for (KeyEvent event : events) {
                        dispatchImeKeyEvent(event);
                    }
                } else {
                    dispatchBridgeText(character);
                }
                offset += Character.charCount(codePoint);
            }
        }

        private final class ForcedImeInputConnection extends BaseInputConnection {
            private final Editable editable = new SpannableStringBuilder();
            private String mirroredComposition = "";

            ForcedImeInputConnection(View targetView) {
                super(targetView, true);
            }

            @Override
            public Editable getEditable() {
                return editable;
            }

            private int commonPrefixLength(String left, String right) {
                int offset = 0;
                int limit = Math.min(left.length(), right.length());
                while (offset < limit) {
                    int leftCodePoint = left.codePointAt(offset);
                    int rightCodePoint = right.codePointAt(offset);
                    if (leftCodePoint != rightCodePoint) {
                        break;
                    }
                    offset += Character.charCount(leftCodePoint);
                }
                return offset;
            }

            private void syncComposingText(String nextText) {
                int commonLength = commonPrefixLength(mirroredComposition, nextText);
                int deleteCount = mirroredComposition.codePointCount(
                    commonLength,
                    mirroredComposition.length()
                );
                for (int index = 0; index < deleteCount; index += 1) {
                    dispatchNativeKey(KeyEvent.KEYCODE_DEL);
                }
                if (commonLength < nextText.length()) {
                    dispatchCommittedText(nextText.substring(commonLength));
                }
                mirroredComposition = nextText;
            }

            private void trimMirroredComposition(int count) {
                for (int index = 0;
                    index < count && !mirroredComposition.isEmpty();
                    index += 1) {
                    int end = mirroredComposition.offsetByCodePoints(
                        mirroredComposition.length(),
                        -1
                    );
                    mirroredComposition = mirroredComposition.substring(0, end);
                }
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                super.setComposingText(text, newCursorPosition);
                syncComposingText(text == null ? "" : text.toString());
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                super.commitText(text, newCursorPosition);
                String committed = text == null ? "" : text.toString();
                if (mirroredComposition.isEmpty()) {
                    dispatchCommittedText(committed);
                } else {
                    syncComposingText(committed);
                    mirroredComposition = "";
                }
                return true;
            }

            @Override
            public boolean finishComposingText() {
                super.finishComposingText();
                mirroredComposition = "";
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                super.deleteSurroundingText(beforeLength, afterLength);
                if (beforeLength > 0) {
                    for (int index = 0; index < beforeLength; index += 1) {
                        dispatchNativeKey(KeyEvent.KEYCODE_DEL);
                    }
                    trimMirroredComposition(beforeLength);
                } else if (afterLength > 0) {
                    for (int index = 0; index < afterLength; index += 1) {
                        dispatchNativeKey(KeyEvent.KEYCODE_FORWARD_DEL);
                    }
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(
                int beforeLength,
                int afterLength
            ) {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
                if (beforeLength > 0) {
                    for (int index = 0; index < beforeLength; index += 1) {
                        dispatchNativeKey(KeyEvent.KEYCODE_DEL);
                    }
                    trimMirroredComposition(beforeLength);
                } else if (afterLength > 0) {
                    for (int index = 0; index < afterLength; index += 1) {
                        dispatchNativeKey(KeyEvent.KEYCODE_FORWARD_DEL);
                    }
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (ironRdpMode) {
                    int keyCode = event.getKeyCode();
                    boolean supportedKey = keyCode == KeyEvent.KEYCODE_DEL
                        || keyCode == KeyEvent.KEYCODE_FORWARD_DEL
                        || keyCode == KeyEvent.KEYCODE_ENTER;
                    if (supportedKey) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            dispatchNativeKey(keyCode);
                        }
                        return true;
                    }
                }
                dispatchImeKeyEvent(event);
                return true;
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                dispatchNativeKey(KeyEvent.KEYCODE_ENTER);
                return true;
            }
        }
    }

    /**
     * Watches for a downward pull from the top edge of the content while the address
     * bar is hidden. Touches still reach the page until the pull is recognized, so
     * taps near the top edge keep working; the page then receives a cancel.
     */
    private final class EdgeGestureLayout extends LinearLayout {
        private float edgePullStartX;
        private float edgePullStartY;
        private boolean trackingEdgePull;
        private boolean consumingEdgePull;

        EdgeGestureLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                trackingEdgePull = false;
                consumingEdgePull = false;
                boolean barHidden = addressBar != null
                    && addressBar.getVisibility() != View.VISIBLE;
                float contentY = event.getY() - getPaddingTop();
                // Fullscreen: a pull from the top edge brings the address bar back.
                // Otherwise a pull down on the address bar shows the zoom slider.
                boolean fromTopEdge = fullscreenEnabled
                    && barHidden
                    && contentY >= 0f
                    && contentY <= dp(40);
                boolean fromAddressBar = !fullscreenEnabled
                    && addressBar != null
                    && addressBar.getVisibility() == View.VISIBLE
                    && event.getY() <= addressBar.getBottom();
                if (fromTopEdge || fromAddressBar) {
                    edgePullStartX = event.getX();
                    edgePullStartY = event.getY();
                    trackingEdgePull = true;
                }
            }
            if (consumingEdgePull) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    consumingEdgePull = false;
                }
                return true;
            }
            if (trackingEdgePull && action == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(event.getX() - edgePullStartX);
                float dy = event.getY() - edgePullStartY;
                if (dy >= dp(24) && dx < dy) {
                    trackingEdgePull = false;
                    consumingEdgePull = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    showAddressBarTemporarily();
                    if (fullscreenEnabled) {
                        // The same edge swipe also brought up the transient system
                        // bars; put them away so the first swipe belongs to the app.
                        // Once the address bar shows, a further swipe keeps them.
                        hideSystemBars();
                        postDelayed(MainActivity.this::hideSystemBars, 250L);
                    }
                    return true;
                }
                if (dy < -dp(8) || dx > dp(48)) {
                    trackingEdgePull = false;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                trackingEdgePull = false;
            }
            return super.dispatchTouchEvent(event);
        }
    }

    private static final class ProjectProfile {
        final String name;
        final String url;

        ProjectProfile(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final class ProjectSession {
        final WebView webView;
        long lastInactiveAt;

        ProjectSession(WebView webView) {
            this.webView = webView;
        }
    }
}
