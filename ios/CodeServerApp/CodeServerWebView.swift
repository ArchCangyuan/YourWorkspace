import SwiftUI
import UIKit
import WebKit

private let desktopViewportWidth = 1280
private let minimumLayoutZoomSteps = -10
private let maximumLayoutZoomSteps = 16
private let layoutZoomFactor = 1.1
private let projectSessionTTL: TimeInterval = 30 * 60
private let maximumHotProjectSessions = 10
private let layoutZoomStepsKey = "layoutZoomSteps"

private let desktopUserAgent = """
Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 \
(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36
""".replacingOccurrences(of: "\n", with: "")

private let keyboardBridgeSource = #"""
(() => {
  // Match the Linux user agent: xterm.js and others detect macOS from
  // navigator.platform, which WKWebView reports as MacIntel in desktop mode.
  try {
    Object.defineProperty(Navigator.prototype, 'platform', {
      configurable: true,
      get: () => 'Linux x86_64'
    });
  } catch (_) {}

  // fitWidth is the native view width in points. When given, the page scale is
  // pinned to fit the new layout width for a moment, because web views keep
  // their old scale on live viewport changes and would crop the page. If the
  // page still does not fit, allowReload lets it fall back to one reload.
  const setViewportWidth = (requestedWidth, fitWidth = 0, allowReload = false) => {
    const numericWidth = Number(requestedWidth) || 1280;
    const width = Math.max(200, Math.min(4000, Math.round(numericWidth)));
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
    if (!candidate) return;
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
    if (current) {
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

  const dispatchKeyPhase = (target, type, key, code, keyCode, source = null) => {
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
    const charCode = type === 'keypress' && printable ? key.codePointAt(0) : 0;
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
      return { code: `Digit${key}`, keyCode: key.charCodeAt(0), shift: false };
    }
    if (key === ' ') return { code: 'Space', keyCode: 32, shift: false };
    const punctuation = {
      '`': ['Backquote', 192, false], '~': ['Backquote', 192, true],
      '-': ['Minus', 189, false], '_': ['Minus', 189, true],
      '=': ['Equal', 187, false], '+': ['Equal', 187, true],
      '[': ['BracketLeft', 219, false], '{': ['BracketLeft', 219, true],
      ']': ['BracketRight', 221, false], '}': ['BracketRight', 221, true],
      '\\': ['Backslash', 220, false], '|': ['Backslash', 220, true],
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
    return { code: '', keyCode: key.codePointAt(0) || 0, shift: false };
  };

  const dispatchCompleteKey = (key, code, keyCode, forceShift = false) => {
    const target = activeTarget();
    if (!target) return false;
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
    return true;
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
      dispatchCompleteKey(key, info.code, info.keyCode, info.shift);
    }
    return true;
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
  document.addEventListener('focusin', (event) => rememberTarget(event.target), true);
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
    touches: new Map(),
    cursorX: -1,
    cursorY: -1,
    // Per mouse button (0 left, 2 right): held down, locked down after
    // a 2 s press, armed (lock reached, finger still on the button),
    // released by the next tap (unlockPending), moved while held.
    buttons: {
      0: { held: false, locked: false, armed: false, unlockPending: false, moved: false, timer: 0 },
      2: { held: false, locked: false, armed: false, unlockPending: false, moved: false, timer: 0 }
    },
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
        font-family: system-ui, sans-serif; font-weight: 600; color: #fff;
        background: rgba(20, 20, 24, 0.42);
        box-shadow: 0 1px 6px rgba(0, 0, 0, 0.35);
        pointer-events: none; touch-action: none;
        user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;
        transition: background-color 120ms;
      }
      .button.pressed { background: rgba(103, 80, 164, 0.62); }
      .button.locked { background: rgba(103, 80, 164, 0.88); }
      /* The outline doubles as the hold-to-lock progress ring. */
      .ring { position: absolute; inset: 0; width: 100%; height: 100%;
        transform: rotate(-90deg); overflow: visible; }
      .ring circle { fill: none; stroke-width: 2.4; }
      .ring .track { stroke: rgba(255, 255, 255, 0.55); }
      .ring .bar { stroke: #d0bcff; stroke-linecap: round;
        stroke-dasharray: 100.53; stroke-dashoffset: 100.53; }
      .button.charging .ring .bar { stroke-dashoffset: 0;
        transition: stroke-dashoffset 2000ms linear; }
      .button.locked .ring .bar { stroke: #fff; stroke-dashoffset: 0; }
      .label { position: relative; line-height: 1; }
    </style>
    <svg class="cursor" viewBox="0 0 14 22">
      <path d="M0.7 0.7 L0.7 18.5 L5.2 14.3 L8.3 21 L11.3 19.7 L8.2 13.1 L13.7 13.1 Z"
        fill="#fff" stroke="#000" stroke-width="1.4" stroke-linejoin="round"/>
    </svg>
    <div class="button left"><svg class="ring" viewBox="0 0 36 36"><circle class="track" cx="18" cy="18" r="16"/><circle class="bar" cx="18" cy="18" r="16"/></svg><span class="label">L</span></div>
    <div class="button right"><svg class="ring" viewBox="0 0 36 36"><circle class="track" cx="18" cy="18" r="16"/><circle class="bar" cx="18" cy="18" r="16"/></svg><span class="label">R</span></div>`;
    mouseMode.host = host;
    mouseMode.cursor = root.querySelector('.cursor');
    mouseMode.left = root.querySelector('.left');
    mouseMode.right = root.querySelector('.right');
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
    for (const [button, element] of [[0, mouseMode.left], [2, mouseMode.right]]) {
      const state = mouseMode.buttons[button];
      element.classList.toggle('pressed', state.held || state.unlockPending);
      element.classList.toggle('locked', state.locked || state.armed);
      element.classList.toggle('charging', Boolean(state.timer));
    }
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
    // Side by side like a mouse, L on the left, near the right edge.
    place(mouseMode.left, 60, 88, 28);
    place(mouseMode.right, 60, 18, 28);
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
    for (const state of Object.values(mouseMode.buttons)) {
      if (!state.held) continue;
      state.moved = true;
      // Dragging with the button held: no lock, so stop the ring.
      if (state.timer) {
        window.clearTimeout(state.timer);
        state.timer = 0;
        updateMouseButtons();
      }
    }
    updateMouseCursor();
    mouseAction('move', x, y);
  };

  // Holding L or R for 2 s (without moving the cursor) locks it down,
  // shown by the ring around the button filling up; the next tap on
  // that button releases it.
  const BUTTON_LOCK_MS = 2000;

  const clearButtonLockTimer = (state) => {
    if (state.timer) window.clearTimeout(state.timer);
    state.timer = 0;
  };

  const mouseButtonDown = (button) => {
    const state = mouseMode.buttons[button];
    if (state.locked) {
      // Tap while locked: release the button when this tap ends.
      state.locked = false;
      state.unlockPending = true;
      updateMouseButtons();
      return;
    }
    state.held = true;
    state.armed = false;
    state.moved = false;
    mouseAction('down', mouseMode.cursorX, mouseMode.cursorY, button);
    clearButtonLockTimer(state);
    state.timer = window.setTimeout(() => {
      state.timer = 0;
      if (state.held && !state.moved) {
        state.armed = true;
        navigator.vibrate?.(20);
      }
      updateMouseButtons();
    }, BUTTON_LOCK_MS);
    updateMouseButtons();
  };

  const mouseButtonUp = (button, cancelled) => {
    const state = mouseMode.buttons[button];
    clearButtonLockTimer(state);
    if (state.unlockPending) {
      state.unlockPending = false;
      state.held = false;
      mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, button);
      updateMouseButtons();
      return;
    }
    if (!state.held) return;
    if (!cancelled && state.armed && !state.moved) {
      state.armed = false;
      state.locked = true;
      updateMouseButtons();
      return;
    }
    state.held = false;
    state.armed = false;
    mouseAction('up', mouseMode.cursorX, mouseMode.cursorY, button);
    updateMouseButtons();
  };

  const releaseMouseButtons = () => {
    for (const held of [0, 2, 1]) {
      if (mouse.buttons & mouseButtonMask(held)) {
        mouseAction('up', mouse.lastX, mouse.lastY, held);
      }
    }
    for (const state of Object.values(mouseMode.buttons)) {
      clearButtonLockTimer(state);
      Object.assign(state, { held: false, locked: false, armed: false, unlockPending: false });
    }
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
        if (pointInElement(mouseMode.left, x, y)) {
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
        if (role === 'left') {
          mouseButtonDown(0);
        } else if (role === 'right') {
          mouseButtonDown(2);
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
        mouseButtonUp(0, cancelled);
      } else if (info.role === 'right') {
        mouseButtonUp(2, cancelled);
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

  const bridge = {
    version: 17,
    forceKeyboard() {
      installRdpGestures();
      const canvas = findIronRdpCanvas();
      if (canvas) {
        state.ironRdpCanvas = canvas;
        rememberTarget(canvas);
        canvas.focus({ preventScroll: true });
        return 'ironrdp';
      }
      rememberTarget(deepestActiveElement(document));
      return activeTarget() ? 'generic' : 'missing';
    },
    installRdpGestures,
    installDesktopGestures,
    sendKey(key, code, keyCode) {
      return dispatchCompleteKey(key, code, keyCode);
    },
    sendText(text) {
      return forwardText(String(text || ''));
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
    setModifiers(control, shift) {
      const nextControl = Boolean(control);
      const nextShift = Boolean(shift);
      const controlChanged = state.control !== nextControl;
      const shiftChanged = state.shift !== nextShift;
      state.control = nextControl;
      state.shift = nextShift;
      if (controlChanged) dispatchModifier('Control', 'ControlLeft', 17, nextControl);
      if (shiftChanged) dispatchModifier('Shift', 'ShiftLeft', 16, nextShift);
    }
  };

  window.__codeServerAppKeyboard = bridge;
  window.__codeServerAppForceKeyboard = () => bridge.forceKeyboard();
})();
"""#

@MainActor
final class CodeServerWebViewStore: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    @Published private(set) var zoomPercent: Int
    @Published private(set) var statusMessage: String?
    @Published private(set) var currentPageAddress = ""
    /// Incremented when the active session finishes loading a page.
    @Published private(set) var pageLoadCount = 0
    /// Incremented when the user pulls down from the top edge of the web content.
    @Published private(set) var topEdgePullCount = 0

    private final class ProjectSession {
        let key: String
        let webView: WKWebView
        var lastInactiveAt: TimeInterval?
        var lastFinishedURL: String?
        var appliedZoomSteps: Int?
        var urlObservation: NSKeyValueObservation?

        init(key: String, webView: WKWebView) {
            self.key = key
            self.webView = webView
        }
    }

    private var sessions: [String: ProjectSession] = [:]
    private weak var hostView: WebViewSessionContainerView?
    private var requestedAddress = ""
    private var activeSessionKey: String?
    private var keepAliveEnabled = false
    private var controlLocked = false
    private var shiftLocked = false
    private var mouseModeEnabled = false
    private var layoutZoomSteps: Int
    private var statusToken = UUID()

    override init() {
        let savedSteps = UserDefaults.standard.integer(forKey: layoutZoomStepsKey)
        layoutZoomSteps = min(max(savedSteps, minimumLayoutZoomSteps), maximumLayoutZoomSteps)
        zoomPercent = Int(round(pow(layoutZoomFactor, Double(layoutZoomSteps)) * 100))
        super.init()
    }

    static func normalizedAddress(_ address: String) -> String {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        if let scheme = URLComponents(string: trimmed)?.scheme?.lowercased(),
           scheme == "http" || scheme == "https" {
            return trimmed
        }
        return "http://\(trimmed)"
    }

    func attach(to view: WebViewSessionContainerView) {
        hostView = view
        view.onCommittedText = { [weak self] text in
            self?.sendText(text)
        }
        view.onDeleteBackward = { [weak self] in
            self?.send(.backspace)
        }
        view.onEnter = { [weak self] in
            self?.send(.enter)
        }
        view.onWidthChanged = { [weak self] in
            self?.syncMouseModeOnAllSessions()
        }
        view.onTopEdgePull = { [weak self] in
            self?.topEdgePullCount += 1
        }
        for session in sessions.values {
            view.install(session.webView)
        }
        if !requestedAddress.isEmpty {
            activate(address: requestedAddress)
        }
    }

    func activate(address: String, restoringSavedAddress: Bool = false) {
        let normalized = Self.normalizedAddress(address)
        guard !normalized.isEmpty else { return }
        requestedAddress = normalized
        guard let hostView else { return }

        let now = Date.timeIntervalSinceReferenceDate
        cleanupExpiredSessions(now: now)

        let target: ProjectSession
        let created: Bool
        if let existing = session(matching: normalized) {
            target = existing
            created = false
        } else {
            let webView = makeWebView(zoomSteps: savedZoomSteps(forKey: normalized))
            target = ProjectSession(key: normalized, webView: webView)
            sessions[normalized] = target
            observeURLChanges(for: target)
            hostView.install(webView)
            created = true
        }

        if let activeSessionKey,
           activeSessionKey != target.key,
           let current = sessions[activeSessionKey] {
            current.lastInactiveAt = now
            // Keep the inactive WKWebView visible behind the active one so WebKit
            // does not suspend its RDP/WebSocket session. It cannot receive input.
            current.webView.isHidden = false
            current.webView.isUserInteractionEnabled = false
        }

        let currentAddress = target.webView.url?.absoluteString ?? ""
        let restoreSavedAddress = restoringSavedAddress
            && !created
            && !Self.addressesEquivalent(currentAddress, normalized)

        activeSessionKey = target.key
        showZoom(of: target.key)
        target.lastInactiveAt = nil
        target.webView.isHidden = false
        target.webView.isUserInteractionEnabled = true
        hostView.bringWebViewToFront(target.webView)

        if (created || restoreSavedAddress), let url = URL(string: normalized) {
            if currentPageAddress != normalized {
                currentPageAddress = normalized
            }
            target.webView.load(URLRequest(url: url))
        } else {
            publishAddress(for: target, fallback: normalized)
            if target.appliedZoomSteps != layoutZoomSteps {
                applyLayoutZoom(to: target, allowFallbackReload: target.webView.url != nil)
            }
        }
        syncModifiers(on: target.webView)
        syncMouseMode(on: target.webView)
        evictExcessSessions()
    }

    func reload() {
        activeSession?.webView.reload()
    }

    nonisolated static let zoomStepRange = minimumLayoutZoomSteps...maximumLayoutZoomSteps

    nonisolated static func zoomPercent(forSteps steps: Int) -> Int {
        Int(round(pow(layoutZoomFactor, Double(steps)) * 100))
    }

    var zoomSteps: Int { layoutZoomSteps }

    func setZoom(steps: Int) {
        let nextSteps = min(max(steps, minimumLayoutZoomSteps), maximumLayoutZoomSteps)
        guard nextSteps != layoutZoomSteps else { return }

        layoutZoomSteps = nextSteps
        UserDefaults.standard.set(layoutZoomSteps, forKey: Self.zoomStepsKey(forKey: activeSessionKey))
        zoomPercent = Self.zoomPercent(forSteps: layoutZoomSteps)
        if let activeSession {
            installUserScripts(
                in: activeSession.webView.configuration.userContentController,
                zoomSteps: layoutZoomSteps
            )
        }
        if let activeSession {
            applyLayoutZoom(
                to: activeSession,
                allowFallbackReload: activeSession.webView.url != nil
            )
        }
    }

    func isSessionHot(_ address: String) -> Bool {
        let normalized = Self.normalizedAddress(address)
        let now = Date.timeIntervalSinceReferenceDate
        cleanupExpiredSessions(now: now)
        guard let session = session(matching: normalized) else { return false }
        if session.key == activeSessionKey { return true }
        guard let inactiveAt = session.lastInactiveAt else { return false }
        if keepAliveEnabled { return true }
        return now - inactiveAt < projectSessionTTL
    }

    func setKeepAliveEnabled(_ enabled: Bool) {
        keepAliveEnabled = enabled
        if !enabled {
            cleanupExpiredSessions(now: Date.timeIntervalSinceReferenceDate)
        }
    }

    func setModifiers(control: Bool, shift: Bool) {
        controlLocked = control
        shiftLocked = shift
        if let webView = activeSession?.webView {
            syncModifiers(on: webView)
        }
        if (control || shift) && !mouseModeEnabled {
            hostView?.activateKeyboardCapture()
        }
    }

    func send(_ key: CodeServerKey) {
        guard let webView = activeSession?.webView else { return }
        let stroke = key.stroke
        let script = """
        window.__codeServerAppKeyboard?.sendKey(
          \(Self.javaScriptString(stroke.key)),
          \(Self.javaScriptString(stroke.code)),
          \(stroke.keyCode)
        ) ?? false;
        """
        webView.evaluateJavaScript(script)
    }

    func sendText(_ text: String) {
        guard !text.isEmpty, let webView = activeSession?.webView else { return }
        let script = """
        window.__codeServerAppKeyboard?.sendText(
          \(Self.javaScriptString(text))
        ) ?? false;
        """
        webView.evaluateJavaScript(script)
    }

    func sendControlC() {
        guard let webView = activeSession?.webView else { return }
        webView.evaluateJavaScript(
            "window.__codeServerAppKeyboard?.sendShortcut('c', 'KeyC', 67, true, false) ?? false;"
        )
    }

    func setMouseModeEnabled(_ enabled: Bool) {
        guard enabled != mouseModeEnabled else { return }
        mouseModeEnabled = enabled
        if enabled {
            // Dismiss any visible system keyboard; the page keeps it hidden afterwards.
            hostView?.endEditing(true)
        }
        syncMouseModeOnAllSessions()
    }

    func announceMouseMode(_ enabled: Bool) {
        showStatus(
            enabled
                ? "Mouse mode: finger moves the cursor, tap or L/R to click"
                : "Mouse mode off"
        )
    }

    func forceKeyboard() {
        guard let session = activeSession else { return }
        session.webView.evaluateJavaScript(
            "window.__codeServerAppForceKeyboard ? window.__codeServerAppForceKeyboard() : false"
        ) { [weak self] value, _ in
            Task { @MainActor [weak self] in
                guard let self,
                      self.activeSessionKey == session.key else { return }
                self.hostView?.activateKeyboardCapture()
                if let mode = value as? String, mode == "ironrdp" {
                    self.showStatus("IronRDP focused – IME connected")
                } else {
                    self.showStatus("Keyboard connected")
                }
            }
        }
    }

    private var activeSession: ProjectSession? {
        guard let activeSessionKey else { return nil }
        return sessions[activeSessionKey]
    }

    private func session(matching normalizedAddress: String) -> ProjectSession? {
        sessions[normalizedAddress]
    }

    private func observeURLChanges(for session: ProjectSession) {
        session.urlObservation = session.webView.observe(\.url, options: [.initial, .new]) {
            [weak self, weak session] webView, _ in
            let address = webView.url?.absoluteString ?? ""
            Task { @MainActor [weak self, weak session] in
                guard let self,
                      let session,
                      self.sessions[session.key] === session else { return }
                self.publishAddress(for: session, fallback: address)
            }
        }
    }

    private func publishAddress(for session: ProjectSession, fallback: String = "") {
        guard session.key == activeSessionKey else { return }
        let address = session.webView.url?.absoluteString ?? fallback
        let normalized = Self.normalizedAddress(address)
        guard normalized.hasPrefix("http://") || normalized.hasPrefix("https://") else {
            return
        }
        if currentPageAddress != normalized {
            currentPageAddress = normalized
        }
    }

    private func makeWebView(zoomSteps: Int) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        configuration.defaultWebpagePreferences.preferredContentMode = .desktop
        installUserScripts(in: configuration.userContentController, zoomSteps: zoomSteps)

        let webView = WKWebView(frame: .zero, configuration: configuration)
        disableDoubleTapZoom(in: webView)
        DispatchQueue.main.async { [weak self, weak webView] in
            guard let self, let webView else { return }
            self.disableDoubleTapZoom(in: webView)
        }
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.customUserAgent = desktopUserAgent
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsLinkPreview = false
        webView.scrollView.keyboardDismissMode = .interactive
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.isHidden = true
        return webView
    }

    private func installUserScripts(in controller: WKUserContentController, zoomSteps: Int) {
        controller.removeAllUserScripts()
        // Seed the zoomed viewport width so the first layout already uses it.
        controller.addUserScript(
            WKUserScript(
                source: "window.__codeServerAppViewportWidth = \(viewportWidth(steps: zoomSteps));",
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            )
        )
        controller.addUserScript(
            WKUserScript(
                source: keyboardBridgeSource,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            )
        )
    }

    private func disableDoubleTapZoom(in view: UIView) {
        for recognizer in view.gestureRecognizers ?? [] {
            guard let tap = recognizer as? UITapGestureRecognizer,
                  tap.numberOfTapsRequired == 2 else { continue }
            tap.isEnabled = false
        }
        for subview in view.subviews {
            disableDoubleTapZoom(in: subview)
        }
    }

    private func syncModifiers(on webView: WKWebView) {
        let script = """
        window.__codeServerAppKeyboard?.setModifiers(
          \(controlLocked ? "true" : "false"),
          \(shiftLocked ? "true" : "false")
        );
        """
        webView.evaluateJavaScript(script)
    }

    private func syncMouseModeOnAllSessions() {
        for session in sessions.values {
            syncMouseMode(on: session.webView)
        }
    }

    private func syncMouseMode(on webView: WKWebView) {
        let width = webView.bounds.width > 0 ? webView.bounds.width : hostView?.bounds.width ?? 0
        webView.evaluateJavaScript(
            "window.__codeServerAppKeyboard?.setMouseMode?.(\(mouseModeEnabled), \(Double(width))) ?? false;"
        )
    }

    /// Each project keeps its own zoom; the global value is the default for new ones.
    private static func zoomStepsKey(forKey key: String?) -> String {
        guard let key else { return layoutZoomStepsKey }
        return "\(layoutZoomStepsKey):\(key)"
    }

    private func savedZoomSteps(forKey key: String?) -> Int {
        let defaults = UserDefaults.standard
        let global = defaults.integer(forKey: layoutZoomStepsKey)
        let keyName = Self.zoomStepsKey(forKey: key)
        let saved = defaults.object(forKey: keyName) == nil ? global : defaults.integer(forKey: keyName)
        return min(max(saved, minimumLayoutZoomSteps), maximumLayoutZoomSteps)
    }

    private func zoomStepsOf(_ session: ProjectSession) -> Int {
        session.key == activeSessionKey ? layoutZoomSteps : savedZoomSteps(forKey: session.key)
    }

    /// Makes the zoom slider show (and set) the zoom of the project in front.
    private func showZoom(of key: String?) {
        layoutZoomSteps = savedZoomSteps(forKey: key)
        let percent = Self.zoomPercent(forSteps: layoutZoomSteps)
        if zoomPercent != percent {
            zoomPercent = percent
        }
    }

    private func viewportWidth(steps: Int) -> Int {
        Int(round(Double(desktopViewportWidth) / pow(layoutZoomFactor, Double(steps))))
    }

    /// Applies the layout zoom in place by changing the virtual viewport width and
    /// pinning the page scale to fit it. The page reloads only when the web view
    /// still does not fit afterwards and a fallback reload is allowed.
    private func applyLayoutZoom(to session: ProjectSession, allowFallbackReload: Bool) {
        let requestedSteps = zoomStepsOf(session)
        let requestedWidth = viewportWidth(steps: requestedSteps)
        let viewWidth = session.webView.bounds.width > 0
            ? session.webView.bounds.width
            : hostView?.bounds.width ?? 0
        let script = """
        (() => {
          const width = \(requestedWidth);
          if (window.__codeServerAppSetViewportWidth) {
            return window.__codeServerAppSetViewportWidth(
              width,
              \(Double(viewWidth)),
              \(allowFallbackReload)
            );
          }
          let viewport = document.querySelector('meta[name="viewport"]');
          if (!viewport) {
            viewport = document.createElement('meta');
            viewport.name = 'viewport';
            (document.head || document.documentElement).appendChild(viewport);
          }
          viewport.content = `width=${width}, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes`;
          window.__codeServerAppViewportWidth = width;
          window.dispatchEvent(new Event('resize'));
          return width;
        })();
        """
        session.webView.evaluateJavaScript(script) { [weak self, weak session] _, _ in
            Task { @MainActor [weak self, weak session] in
                guard let self,
                      let session,
                      self.sessions[session.key] === session,
                      requestedSteps == self.zoomStepsOf(session) else { return }
                session.appliedZoomSteps = requestedSteps
            }
        }
    }

    private func cleanupExpiredSessions(now: TimeInterval) {
        guard !keepAliveEnabled else { return }
        let expiredKeys = sessions.compactMap { key, session -> String? in
            guard key != activeSessionKey,
                  let inactiveAt = session.lastInactiveAt,
                  now - inactiveAt >= projectSessionTTL else { return nil }
            return key
        }
        for key in expiredKeys {
            destroySession(key: key)
        }
    }

    private func evictExcessSessions() {
        while sessions.count > maximumHotProjectSessions {
            let candidate = sessions.values
                .filter { $0.key != activeSessionKey }
                .min { ($0.lastInactiveAt ?? 0) < ($1.lastInactiveAt ?? 0) }
            guard let candidate else { return }
            destroySession(key: candidate.key)
        }
    }

    private func destroySession(key: String) {
        guard let session = sessions.removeValue(forKey: key) else { return }
        session.urlObservation = nil
        session.webView.stopLoading()
        session.webView.navigationDelegate = nil
        session.webView.uiDelegate = nil
        session.webView.removeFromSuperview()
    }

    private func showStatus(_ message: String) {
        let token = UUID()
        statusToken = token
        statusMessage = message
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            guard let self, self.statusToken == token else { return }
            self.statusMessage = nil
        }
    }

    private static func javaScriptString(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [value]),
              let encoded = String(data: data, encoding: .utf8),
              encoded.count >= 2 else { return "\"\"" }
        return String(encoded.dropFirst().dropLast())
    }

    private static func addressesEquivalent(_ first: String, _ second: String) -> Bool {
        comparableAddress(first) == comparableAddress(second)
    }

    private static func comparableAddress(_ address: String) -> String {
        let normalized = normalizedAddress(address)
        guard var components = URLComponents(string: normalized) else {
            return normalized
        }
        if components.path == "/" {
            components.path = ""
        } else if components.path.hasSuffix("/") {
            components.path.removeLast()
        }
        return components.string ?? normalized
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        if let session = sessions.values.first(where: { $0.webView === webView }),
           session.lastFinishedURL != navigationAction.request.url?.absoluteString {
            session.appliedZoomSteps = nil
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        disableDoubleTapZoom(in: webView)
        guard let session = sessions.values.first(where: { $0.webView === webView }) else {
            return
        }
        session.lastFinishedURL = webView.url?.absoluteString
        publishAddress(for: session)
        if session.key == activeSessionKey {
            pageLoadCount += 1
        }
        let steps = zoomStepsOf(session)
        let zoomChanged = (session.appliedZoomSteps == nil && steps != 0)
            || (session.appliedZoomSteps != nil && session.appliedZoomSteps != steps)
        applyLayoutZoom(to: session, allowFallbackReload: zoomChanged)
        syncModifiers(on: webView)
        syncMouseMode(on: webView)
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        // Links opened with window.open (e.g. terminal and editor links) go to
        // the system browser instead of a new in-app window.
        if let url = navigationAction.request.url,
           let scheme = url.scheme?.lowercased(),
           ["http", "https", "mailto"].contains(scheme) {
            UIApplication.shared.open(url)
        }
        return nil
    }
}

struct CodeServerWebView: UIViewRepresentable {
    let address: String
    let store: CodeServerWebViewStore
    let mouseModeEnabled: Bool

    func makeUIView(context: Context) -> WebViewSessionContainerView {
        let view = WebViewSessionContainerView()
        store.attach(to: view)
        return view
    }

    func updateUIView(_ view: WebViewSessionContainerView, context: Context) {
        store.attach(to: view)
        store.activate(address: address)
        store.setMouseModeEnabled(mouseModeEnabled)
    }
}

final class WebViewSessionContainerView: UIView {
    var onCommittedText: ((String) -> Void)? {
        didSet { keyboardCapture.onCommittedText = onCommittedText }
    }
    var onDeleteBackward: (() -> Void)? {
        didSet { keyboardCapture.onDeleteBackward = onDeleteBackward }
    }
    var onEnter: (() -> Void)? {
        didSet { keyboardCapture.onEnter = onEnter }
    }
    var onWidthChanged: (() -> Void)?
    var onTopEdgePull: (() -> Void)?

    private let keyboardCapture = KeyboardCaptureTextView()
    private var lastLayoutWidth: CGFloat = 0
    fileprivate let topEdgeZoneHeight: CGFloat = 32
    private let topEdgePullDistance: CGFloat = 48
    private var topEdgePullFired = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .systemBackground
        addSubview(keyboardCapture)

        // Recognizes a downward pull that starts at the top edge without blocking
        // taps there; the web view keeps receiving touches until the pull begins.
        let topEdgePull = UIPanGestureRecognizer(
            target: self,
            action: #selector(handleTopEdgePull(_:))
        )
        topEdgePull.delegate = self
        addGestureRecognizer(topEdgePull)
    }

    @objc private func handleTopEdgePull(_ recognizer: UIPanGestureRecognizer) {
        switch recognizer.state {
        case .began:
            topEdgePullFired = false
        case .changed:
            let translation = recognizer.translation(in: self)
            if !topEdgePullFired,
               translation.y >= topEdgePullDistance,
               abs(translation.x) < translation.y {
                topEdgePullFired = true
                onTopEdgePull?()
            }
        default:
            topEdgePullFired = false
        }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func install(_ webView: WKWebView) {
        if webView.superview !== self {
            addSubview(webView)
        }
        webView.frame = bounds
        bringSubviewToFront(keyboardCapture)
    }

    func bringWebViewToFront(_ webView: WKWebView) {
        bringSubviewToFront(webView)
        bringSubviewToFront(keyboardCapture)
    }

    func activateKeyboardCapture() {
        bringSubviewToFront(keyboardCapture)
        keyboardCapture.activate()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        for subview in subviews where subview !== keyboardCapture {
            subview.frame = bounds
        }
        keyboardCapture.frame = CGRect(
            x: 1,
            y: max(bounds.height - 2, 1),
            width: 1,
            height: 1
        )
        if bounds.width != lastLayoutWidth {
            lastLayoutWidth = bounds.width
            onWidthChanged?()
        }
    }
}

extension WebViewSessionContainerView: UIGestureRecognizerDelegate {
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return true }
        let translation = pan.translation(in: self)
        let start = CGPoint(
            x: pan.location(in: self).x - translation.x,
            y: pan.location(in: self).y - translation.y
        )
        return start.y <= topEdgeZoneHeight
            && translation.y > 0
            && abs(translation.x) < translation.y
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }
}

private final class KeyboardCaptureTextView: UITextView, UITextViewDelegate {
    var onCommittedText: ((String) -> Void)?
    var onDeleteBackward: (() -> Void)?
    var onEnter: (() -> Void)?
    private var isFlushing = false

    init() {
        super.init(frame: .zero, textContainer: nil)
        delegate = self
        backgroundColor = .clear
        textColor = .clear
        tintColor = .clear
        alpha = 0.01
        isScrollEnabled = false
        autocorrectionType = .no
        autocapitalizationType = .none
        spellCheckingType = .no
        smartDashesType = .no
        smartQuotesType = .no
        smartInsertDeleteType = .no
        keyboardType = .default
        returnKeyType = .default
        accessibilityElementsHidden = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func activate() {
        text = ""
        becomeFirstResponder()
    }

    override func deleteBackward() {
        if markedTextRange == nil && text.isEmpty {
            onDeleteBackward?()
            return
        }
        super.deleteBackward()
    }

    func textViewDidChange(_ textView: UITextView) {
        guard !isFlushing,
              markedTextRange == nil,
              !text.isEmpty else { return }

        let committed = text ?? ""
        isFlushing = true
        text = ""
        isFlushing = false

        var buffer = ""
        for character in committed {
            if character == "\n" || character == "\r" {
                if !buffer.isEmpty {
                    onCommittedText?(buffer)
                    buffer = ""
                }
                onEnter?()
            } else {
                buffer.append(character)
            }
        }
        if !buffer.isEmpty {
            onCommittedText?(buffer)
        }
    }
}
