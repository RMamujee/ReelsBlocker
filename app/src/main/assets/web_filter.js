// Reels Blocker's filter for Instagram's website, injected before Instagram's own scripts.
// Like a browser ad blocker it changes the page itself: unwanted things are removed as they
// are added, before they are drawn, so nothing is covered or scrolled past.
//
// __rbCfg is a closure variable FeedActivity wraps around this script, from the switches on
// the main screen; Instagram's own scripts can't see it.
(function () {
  'use strict';
  if (window.__rbFilter) return;
  window.__rbFilter = true;

  var cfg = (typeof __rbCfg !== 'undefined' && __rbCfg) || {};
  // cfg.app: running in the Android app (SilentFeed). The same filter also runs in Safari
  // on iPhone as a userscript, where calls and the emoji fix (both Android-only) stay off.
  // The call window (call.instagram.com) gets only the call patches below, none of the feed
  // rules (its "/" is the call, not the feed).
  if (/^call\./.test(location.hostname)) cfg = { debug: cfg.debug, app: cfg.app };
  var log = cfg.debug ? function () { console.log.apply(console, ['[rb]'].concat([].slice.call(arguments))); } : function () {};

  var AD_LABELS = /^(sponsored|ad|paid partnership)$/i;
  var SUGGESTED_LABELS = /^(suggested for you|suggestions for you|suggested posts|suggested reels|suggested for you to follow)$/i;
  // Instagram's prompts to switch to the app.
  var APP_PROMPT = /^(use the app|open instagram|get the app|open the app|see more in the app|switch to the app|use the instagram app)$/i;
  var FOLLOW_LABELS = /^(follow|follow back)$/i;
  var HIDDEN = 'data-rb-hidden';

  // ---- Styles: things hidden purely by CSS ----------------------------------------------
  // The check mark Instagram shows when you're caught up: a ring with a tick, in its gradient.
  var CHECK_SVG = 'data:image/svg+xml,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 96 96">' +
    '<defs><linearGradient id="g" x1="0" y1="1" x2="1" y2="0">' +
    '<stop offset="0" stop-color="#f9ce34"/><stop offset=".5" stop-color="#ee2a7b"/><stop offset="1" stop-color="#6228d7"/>' +
    '</linearGradient></defs>' +
    '<circle cx="48" cy="48" r="44" fill="none" stroke="url(#g)" stroke-width="4"/>' +
    '<path d="M30 49l12 12 24-26" fill="none" stroke="url(#g)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>' +
    '</svg>');

  var css = [
    '[' + HIDDEN + '] { display: none !important; }',
    '[data-rb-feed] { padding-bottom: 0 !important; }',
    // Caught up: the feed ends at the last new post with Instagram's check mark under it, and
    // the loading spinner (which would fetch older posts forever) goes.
    // The feed is a flex column, so its ::after (check mark and title) and ::before (the "See
    // older posts" link) can both be ordered after the posts. Colours are Instagram's own, so
    // it matches light and dark mode.
    'html.rb-caught [data-rb-feed]::after { order: 2147483646; content: "You\'re all caught up"; display: block; margin: 8px 16px 0;' +
      ' padding: 96px 16px 0; border-top: 1px solid rgb(var(--ig-separator, 38, 38, 38));' +
      ' background: url("' + CHECK_SVG + '") center 24px / 56px 56px no-repeat;' +
      ' text-align: center; font-size: 16px; font-weight: 600; line-height: 22px; color: rgb(var(--ig-primary-text, 245, 245, 245)); }',
    'html.rb-caught [data-rb-feed]::before { order: 2147483647; content: "See older posts"; display: block; padding: 16px 16px 48px;' +
      ' text-align: center; font-size: 14px; font-weight: 600; line-height: 18px; color: rgb(var(--ig-primary-button, 0, 149, 246)); cursor: pointer; }',
    'html.rb-caught main [role="progressbar"], html.rb-caught main svg[aria-label="Loading..."] { display: none !important; }',
    // "Open the app" banners and prompts.
    'a[href*="/download/"], a[href^="intent:"], a[href*="itunes.apple.com"], a[href*="play.google.com"] { display: none !important; }',
    // Bottom bar order: Home, Messages, (Reels), Search, Profile.
    '[data-rb-tabs] > :has(a[href="/"]) { order: 0; }',
    '[data-rb-tabs] > :has(a[href^="/direct/"]) { order: 1; }',
    '[data-rb-tabs] > :has(a[href^="/reels/"]) { order: 2; }',
    '[data-rb-tabs] > :has(a[href="/explore/"]) { order: 3; }',
    '[data-rb-tabs] > * { order: 4; }'
  ];
  if (cfg.blockReels) {
    // The Reels tab (its whole slot, so the other icons stay evenly spaced) and any link to
    // the Reels feed.
    css.push('a[href="/reels/"], a[href^="/reels/"], [data-rb-tabs] > :has(a[href^="/reels/"]) { display: none !important; }');
  }
  if (cfg.hideExplore) {
    // On the Explore page the grid tiles are links to posts and reels; search results are
    // links to profiles, tags and places, so they stay.
    css.push('html.rb-explore main a[href*="/p/"], html.rb-explore main a[href*="/reel/"] { display: none !important; }');
    css.push('html.rb-explore main [role="progressbar"] { display: none !important; }');
  }
  // Safari runs this before the page has even an <html> element (Android's WebView has one
  // by then): anything that needs it waits for it, which is still before anything is drawn.
  function whenRoot(fn) {
    if (document.documentElement) { fn(); return; }
    var wait = new MutationObserver(function () {
      if (!document.documentElement) return;
      wait.disconnect();
      fn();
    });
    wait.observe(document, { childList: true });
  }
  function addStyle() {
    if (document.getElementById('rb-style')) return;
    var style = document.createElement('style');
    style.id = 'rb-style';
    style.textContent = css.join('\n');
    (document.head || document.documentElement).appendChild(style);
  }
  whenRoot(addStyle);

  // ---- Routes: Reels pages never open, the Explore class follows the URL ----------------
  // Home is Instagram's Following feed: only accounts you follow, newest
  // first, so nothing has to be downloaded and then thrown away, and it can end.
  var FOLLOWING = '/?variant=following';
  function onFollowingFeed() {
    return location.pathname === '/' && /[?&]variant=following\b/.test(location.search);
  }
  // Instagram's router follows the address on popstate, so this switches feeds in place
  // without reloading the page.
  function goFollowing(replace) {
    history[replace ? 'replaceState' : 'pushState'](null, '', FOLLOWING);
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }
  if (cfg.followingFeed) {
    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest && e.target.closest('a[href="/"]');
      if (!a || e.button !== 0 || e.metaKey || e.ctrlKey) return;
      // Only inside the logged-in app, where Instagram's router is listening (login and
      // account pages are plain pages: their links work as normal).
      if (!document.querySelector('[data-rb-nav]')) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      if (!onFollowingFeed()) goFollowing(false);
      // Tapping Home on Home goes back to the top, and at the top refreshes, as in the app.
      else if (scrollY > 4) window.scrollTo({ top: 0, behavior: 'smooth' });
      else location.reload();
    }, true);
  }

  var lastPath = '';
  function onRoute() {
    var path = location.pathname + location.search;
    if (path === lastPath) return;
    var from = lastPath;
    lastPath = path;
    // Back from watching stories: the rings show what's now been seen.
    if (cfg.followingFeed && /^\/stories\//.test(from) && onFollowingFeed()) fetchTray(true);
    if (cfg.followingFeed && location.pathname === '/' && !/[?&]variant=/.test(location.search)) {
      // Reached the For-you feed some other way (the logo, after logging in): swap it out.
      setTimeout(function () { if (location.pathname === '/' && !/[?&]variant=/.test(location.search)) goFollowing(true); }, 0);
    }
    if (stale && onFollowingFeed()) {
      location.reload();
      return;
    }
    path = location.pathname;
    document.documentElement.classList.toggle('rb-explore', /^\/explore\/?$/.test(path) || /^\/explore\/(tags|locations)\//.test(path));
    document.documentElement.classList.toggle('rb-stories', /^\/stories\//.test(path));
    // /reels/ (and /reels/<id>/) is the endless swipe-through Reels feed: never opens.
    // /reel/<id>/ and /<user>/reel/<id>/ are single posts with one video, like any other
    // post from a friend or a chat, so they open normally.
    if (cfg.blockReels && /^\/reels(\/[^/]+)?\/?$/.test(path)) {
      log('blocked route', path);
      if (history.length > 1) history.back(); else location.replace('/');
    }
    log('route', path);
    setCaught();
    placeCallBar();
    if (cfg.followingFeed) placeTray();
    checkStory();
  }
  function wrap(name) {
    var orig = history[name];
    history[name] = function (state, title, url) {
      // The story viewer leaving for the feed when it runs out: on to the next person instead.
      if (nextStoryInstead(url)) return;
      var r = orig.apply(this, arguments);
      onRoute();
      return r;
    };
  }
  wrap('pushState');
  wrap('replaceState');
  // The viewer usually leaves by going back (history.back()) to the page it was opened from.
  // That's the page's own call: the system Back button goes back without it.
  var histBack = history.back, histGo = history.go;
  history.back = function () {
    if (nextStoryInstead('/', 'back')) return;
    return histBack.apply(this, arguments);
  };
  history.go = function (n) {
    if (n < 0 && nextStoryInstead('/', 'go')) return;
    return histGo.apply(this, arguments);
  };
  window.addEventListener('popstate', onRoute);

  // ---- Feed: posts that aren't from people you follow are removed -----------------------
  // Leaf text nodes in a post's header (its first few leaves) carry the markers.
  function leaves(root, max) {
    var out = [];
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = walker.nextNode()) && out.length < max) {
      var t = n.nodeValue.trim();
      if (t) out.push(t);
    }
    return out;
  }
  function postReason(article) {
    var head = leaves(article, 14);
    // A Follow button only means "suggested" in the home feed; a post the user opened
    // (from a link, a chat or a profile) shows whatever account posted it.
    var onFeed = location.pathname === '/';
    for (var i = 0; i < head.length; i++) {
      if (cfg.hideAds && AD_LABELS.test(head[i])) return 'ad';
      if (cfg.hideSuggested && onFeed && (SUGGESTED_LABELS.test(head[i]) || FOLLOW_LABELS.test(head[i]))) return 'suggested';
    }
    if (cfg.followingFeed && !showOlder && onFollowingFeed()) {
      var t = postTime(article);
      if (t && t <= cutoff) return 'older';
      if (t > newestShown) newestShown = t;
    }
    return null;
  }

  // ---- Caught up: the Following feed ends where you left off ---------------------------
  // It's newest first, so once a post is one you'd already reached last time (or is over two
  // days old), everything below it is too: from there the feed shows Instagram's check mark
  // instead of loading older posts forever. "See older posts" under it brings them back.
  // What counts as seen only moves on when you actually scroll down to the check mark, so a
  // visit cut short never hides posts you didn't get to.
  var SEEN_KEY = 'rb-seen-top';
  var MAX_AGE = 2 * 24 * 3600 * 1000;
  var seenTop = 0;
  try { seenTop = +localStorage.getItem(SEEN_KEY) || 0; } catch (e) {}
  // Fixed for this page: posts already on screen must not vanish as the mark moves on. (The
  // page is reloaded when you come back to it after a while; see below.)
  var cutoff = Math.max(seenTop, Date.now() - MAX_AGE);
  var newestShown = 0;
  var showOlder = false;
  function postTime(article) {
    var t = article.querySelector('time[datetime]');
    return (t && Date.parse(t.getAttribute('datetime'))) || 0;
  }
  // Caught up is read off the page as it is now: the feed list on screen holds a post hidden
  // as older. (A flag would go stale when Instagram rebuilds the feed.)
  function isCaught() {
    // The list keeps its mark while it lives, even when Instagram's virtual list drops the
    // hidden posts themselves while you're scrolled far up.
    return !showOlder && onFollowingFeed() && !!document.querySelector('[data-rb-list]');
  }
  function setCaught() {
    document.documentElement.classList.toggle('rb-following', !!cfg.followingFeed && onFollowingFeed());
    document.documentElement.classList.toggle('rb-caught', isCaught());
  }
  function checkReachedEnd() {
    if (newestShown <= seenTop || !isCaught()) return;
    var list = document.querySelector('[data-rb-list]');
    if (!list || list.getBoundingClientRect().bottom > innerHeight + 120) return;
    seenTop = newestShown;
    try { localStorage.setItem(SEEN_KEY, String(seenTop)); } catch (e) {}
    log('caught up to', new Date(seenTop).toISOString());
  }
  var scrollQueued = false;
  window.addEventListener('scroll', function () {
    if (scrollQueued) return;
    scrollQueued = true;
    requestAnimationFrame(function () { scrollQueued = false; checkReachedEnd(); });
  }, { passive: true });

  // Coming back after a while: the page has been kept alive in the background, so the feed
  // (and what counts as new) is reloaded, the way the app refreshes on return.
  var STALE_AFTER = 15 * 60 * 1000;
  var hiddenAt = 0;
  var stale = false;
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') { hiddenAt = Date.now(); return; }
    if (hiddenAt && Date.now() - hiddenAt > STALE_AFTER) {
      if (onFollowingFeed()) location.reload(); else stale = true;
    }
  });

  // ---- Requests held back -------------------------------------------------------------
  // Once caught up, Instagram would keep fetching pages of older posts (with their photos and
  // videos, several MB a minute) only for them to be hidden. Its request for the next page is
  // held instead, unsent, and sent if you ask for older posts.
  // Story ads come in a request of their own (the stories "ads pool"). Like an ad blocker,
  // that request is never sent: Instagram gets no story ads to show and, still waiting for
  // them, doesn't ask again.
  var PAGINATION = /fb_api_req_friendly_name=PolarisFeedRootPagination/;
  var ADS_POOL = /fb_api_req_friendly_name=[A-Za-z0-9]*AdsPool/;
  var heldPage = null;
  var adsBlocked = 0;
  function isGraphql(url, body, re) {
    return /\/graphql\/query|\/api\/graphql/.test(url || '') && (typeof body === 'string' || body instanceof URLSearchParams) && re.test(String(body));
  }
  function countBlockedAd() {
    adsBlocked++;
    document.documentElement.setAttribute('data-rb-ads-blocked', String(adsBlocked));
    log('blocked a story ads request');
  }
  var xhrOpen = XMLHttpRequest.prototype.open;
  var xhrSend = XMLHttpRequest.prototype.send;
  var xhrAbort = XMLHttpRequest.prototype.abort;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__rbUrl = String(url);
    if (heldPage && heldPage.xhr === this) heldPage = null;
    return xhrOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.abort = function () {
    if (heldPage && heldPage.xhr === this) heldPage = null;
    return xhrAbort.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    if (cfg.hideAds && isGraphql(this.__rbUrl, body, ADS_POOL)) {
      countBlockedAd();
      return;
    }
    if (isGraphql(this.__rbUrl, body, PAGINATION) && isCaught()) {
      // Only the latest is kept: an earlier one was given up on (a retry replaces it).
      heldPage = { xhr: this, body: body };
      log('held a page of older posts');
      return;
    }
    return xhrSend.apply(this, arguments);
  };
  if (cfg.hideAds && window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      if (init && isGraphql(url, init.body, ADS_POOL)) {
        countBlockedAd();
        return new Promise(function () {});
      }
      return origFetch.apply(this, arguments);
    };
  }
  function releaseHeld() {
    var h = heldPage;
    heldPage = null;
    if (!h) return;
    try { xhrSend.call(h.xhr, h.body); } catch (e) { log('held page request failed', e); }
  }

  // The note under the last post is the list's ::after and ::before, so a tap on it lands on
  // the list itself.
  document.addEventListener('click', function (e) {
    var list = e.target;
    if (!list || !list.hasAttribute || !list.hasAttribute('data-rb-list') || !isCaught()) return;
    // Only the "See older posts" line (the last 82px of the list) opens them.
    if (e.clientY < list.getBoundingClientRect().bottom - 82) return;
    showOlder = true;
    setCaught();
    var older = document.querySelectorAll('[' + HIDDEN + '="older"]');
    for (var i = 0; i < older.length; i++) older[i].removeAttribute(HIDDEN);
    releaseHeld();
    log('showing older posts');
  }, true);
  function checkArticle(article) {
    if (article.hasAttribute(HIDDEN)) return;
    var reason = postReason(article);
    if (reason) {
      article.setAttribute(HIDDEN, reason);
      // Instagram's feed is a virtual list that pads its bottom with the space it expects
      // the posts below to take, counting removed ones, which leaves blank screens. The list
      // ends at its last real post instead.
      var parent = article.parentElement;
      if (parent) {
        parent.setAttribute('data-rb-feed', '');
        // The list the check mark goes under: the one holding the older posts.
        if (reason === 'older') parent.setAttribute('data-rb-list', '');
      }
      if (reason === 'older') {
        setCaught();
        checkReachedEnd();
      }
      log('hid post:', reason);
    }
  }
  // "Suggested for you" carousels and section headings outside posts.
  function checkSection(el) {
    if (!cfg.hideSuggested) return;
    var t = (el.textContent || '').trim();
    if (!SUGGESTED_LABELS.test(t)) return;
    // Hide the heading's block: the nearest ancestor that sits directly in the feed column.
    var block = el;
    for (var i = 0; i < 6 && block.parentElement; i++) {
      if (block.parentElement.querySelector('article') && block.parentElement !== block) break;
      block = block.parentElement;
    }
    if (block && block !== document.body && !block.querySelector('article:not([' + HIDDEN + '])')) {
      block.setAttribute(HIDDEN, 'suggested-section');
      log('hid suggested section');
    }
  }

  // ---- "Use the app" prompts: removed, and the page they lock is unlocked ---------------
  function checkAppPrompt(el) {
    var t = (el.textContent || '').trim();
    if (!APP_PROMPT.test(t)) return;
    var box = el.closest('[role="dialog"], [role="presentation"], [role="banner"]');
    if (!box) {
      // A banner or sheet without a role: the nearest ancestor that floats over the page.
      for (var e = el.parentElement; e && e !== document.body; e = e.parentElement) {
        var pos = getComputedStyle(e).position;
        if (pos === 'fixed' || pos === 'sticky') { box = e; break; }
      }
    }
    if (!box || box.hasAttribute(HIDDEN) || box.querySelector('article')) return;
    box.setAttribute(HIDDEN, 'app-prompt');
    // Popups lock scrolling on the page behind them.
    document.body.style.removeProperty('overflow');
    document.documentElement.style.removeProperty('overflow');
    log('removed app prompt');
  }

  // ---- Typing: Instagram's bottom bar would float over the keyboard ----------------------
  document.addEventListener('focusin', function (e) {
    if (e.target && e.target.matches && e.target.matches('input, textarea, [contenteditable="true"]')) {
      document.documentElement.classList.add('rb-typing');
    }
  }, true);
  document.addEventListener('focusout', function () {
    document.documentElement.classList.remove('rb-typing');
  }, true);
  css.push('html.rb-typing [data-rb-nav] { display: none !important; }');
  // The bottom bar: the fixed block holding the Home and search links.
  // Its row of tabs (the flex box holding one slot per tab) is marked too, so the CSS above can
  // reorder the slots without touching Instagram's elements.
  function markNav() {
    var link = document.querySelector('a[href="/explore/"]');
    for (var e = link && link.parentElement; e && e !== document.body; e = e.parentElement) {
      if (!e.hasAttribute('data-rb-tabs') && e.querySelector('a[href^="/direct/"]') && e.children.length >= 4) {
        e.setAttribute('data-rb-tabs', '');
      }
      if (getComputedStyle(e).position === 'fixed') { e.setAttribute('data-rb-nav', ''); return; }
    }
  }

  // ---- Calls: Instagram's website calls, switched on for the phone ----------------------
  // The website has voice and video calls, but only lets desktop browsers use them
  // (PolarisCallGatingHelpers.isWebCallingEnabled checks for a desktop). Rather than pose as a
  // desktop, which would switch the whole site to its desktop layout, only that check is
  // changed, as each module is defined: incoming calls ring, and the call window works. The
  // phone layout has no call buttons in a chat, so the filter adds them to the chat's header.
  var callsPatched = false;
  var patchedModules = {};
  var MODULE_PATCHES = {
    PolarisCallGatingHelpers: function (x) {
      x.isWebCallingEnabled = function () { return true; };
      x.isIncomingCallEnabled = function () { return true; };
    },
    RTWebIncomingRingConfiguration: function (x) {
      if (typeof x.isSupportedClient === 'function') x.isSupportedClient = function () { return true; };
      if (typeof x.isSupportedOutboundClient === 'function') x.isSupportedOutboundClient = function () { return true; };
    }
  };
  // Instagram defines its modules with __d(name, deps, factory, ...); its factories get the
  // module's exports object as their 7th argument.
  if (cfg.app) (function () {
    var realDefine;
    function wrapDefine(fn) {
      if (typeof fn !== 'function') return fn;
      return function (name, deps, factory) {
        var patch = MODULE_PATCHES[name];
        if (patch && typeof factory === 'function') {
          arguments[2] = function () {
            var r = factory.apply(this, arguments);
            try {
              if (arguments[6]) patch(arguments[6]);
              patchedModules[name] = true;
              if (patchedModules.PolarisCallGatingHelpers && patchedModules.RTWebIncomingRingConfiguration) callsPatched = true;
            } catch (e) {}
            return r;
          };
        }
        return fn.apply(this, arguments);
      };
    }
    try {
      Object.defineProperty(window, '__d', {
        configurable: true,
        get: function () { return realDefine; },
        set: function (fn) { realDefine = wrapDefine(fn); }
      });
    } catch (e) {}
  })();
  // If a module was defined before the hook (or around it), it's patched in place once it
  // exists. Instagram's code reads these functions off the exports object on every call.
  var lastPatchTry = 0;
  var patchTries = 0;
  function patchCallModules() {
    // Tried at most every 2s, 30 times: asking for a module that isn't there is an error to
    // Instagram. Each module is patched once, on its own.
    if (!cfg.app || callsPatched || patchTries >= 30 || typeof window.require !== 'function' || Date.now() - lastPatchTry < 2000) return;
    lastPatchTry = Date.now();
    patchTries++;
    var names = Object.keys(MODULE_PATCHES);
    var left = 0;
    for (var i = 0; i < names.length; i++) {
      if (patchedModules[names[i]]) continue;
      try { MODULE_PATCHES[names[i]](window.require(names[i])); patchedModules[names[i]] = true; } catch (e) { left++; }
    }
    if (patchedModules.PolarisCallGatingHelpers && !left) callsPatched = true;
  }

  // Call buttons in a chat's header: Instagram's own launchCall, as its desktop buttons use.
  // Icons are built element by element (the page may only accept vetted HTML strings).
  var SVG_NS = 'http://www.w3.org/2000/svg';
  function icon(shapes) {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('width', '24');
    svg.setAttribute('height', '24');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '2');
    svg.setAttribute('stroke-linejoin', 'round');
    for (var i = 0; i < shapes.length; i++) {
      var el = document.createElementNS(SVG_NS, shapes[i][0]);
      for (var k in shapes[i][1]) el.setAttribute(k, shapes[i][1][k]);
      svg.appendChild(el);
    }
    return svg;
  }
  var PHONE_ICON = [['path', { d: 'M18.23 15.33l-2.44-1.22a1.5 1.5 0 0 0-1.73.28l-1.06 1.06a11.2 11.2 0 0 1-4.45-4.45l1.06-1.06a1.5 1.5 0 0 0 .28-1.73L8.67 5.77A1.5 1.5 0 0 0 6.83 5L4.9 5.64A1.5 1.5 0 0 0 3.9 7.2 15.5 15.5 0 0 0 16.8 20.1a1.5 1.5 0 0 0 1.56-1L19 17.17a1.5 1.5 0 0 0-.77-1.84z' }]];
  var VIDEO_ICON = [['rect', { x: '2', y: '6', width: '14', height: '12', rx: '2.5' }], ['path', { d: 'M16 10.5l5-3v9l-5-3z' }]];
  var callBar = null;
  function threadId() {
    var m = /^\/direct\/t\/([^/]+)\/?$/.exec(location.pathname);
    return m ? m[1] : null;
  }
  // A short record of call windows opened (when, and which page: no names or content), to
  // see where a call that doesn't start goes wrong.
  if (window.open) {
    var winOpen = window.open;
    window.open = function (url) {
      var w = winOpen.apply(this, arguments);
      try {
        var u = new URL(String(url || ''), location.href);
        var t = JSON.parse(sessionStorage.getItem('rb-call-log') || '[]');
        t.push(new Date().toISOString().slice(11, 19) + ' ' + u.host + u.pathname + (w ? ' opened' : ' refused'));
        sessionStorage.setItem('rb-call-log', JSON.stringify(t.slice(-20)));
      } catch (e) {}
      return w;
    };
  }
  function startCall(video) {
    var id = threadId();
    if (!id) return;
    patchCallModules();
    try {
      window.require('PolarisCallNavigator').launchCall(id, video, null);
    } catch (e) {
      window.open('/call/?has_video=' + video + '&ig_thread_id=' + encodeURIComponent(id), '_blank');
    }
  }
  function makeCallBar() {
    var bar = document.createElement('div');
    bar.id = 'rb-call-bar';
    [['Audio call', 'false', PHONE_ICON], ['Video call', 'true', VIDEO_ICON]].forEach(function (d) {
      var b = document.createElement('button');
      b.type = 'button';
      b.setAttribute('aria-label', d[0]);
      b.setAttribute('data-video', d[1]);
      b.appendChild(icon(d[2]));
      bar.appendChild(b);
    });
    bar.addEventListener('click', function (e) {
      var b = e.target.closest('button');
      if (b) startCall(b.getAttribute('data-video') === 'true');
    });
    return bar;
  }
  css.push('#rb-call-bar { position: fixed; z-index: 10; display: flex; gap: 4px; }');
  css.push('#rb-call-bar button { width: 40px; height: 40px; padding: 8px; border: 0; background: none; color: rgb(var(--ig-primary-text, 245, 245, 245)); cursor: pointer; }');
  css.push('#rb-call-bar svg { display: block; }');
  // Placed left of the right-most icon in the chat's top bar (its info button), or at the
  // right edge if there isn't one.
  function placeCallBar() {
    var id = threadId();
    // With calling switched on, Instagram shows its own call buttons in the chat's header:
    // those are used, and these are only a stand-in for when they're missing.
    var own = document.querySelector('svg[aria-label="Audio call"], svg[aria-label="Video call"]');
    if (!cfg.app || !id || !document.body || own) {
      if (callBar && callBar.parentNode) callBar.parentNode.removeChild(callBar);
      return;
    }
    if (!callBar) callBar = makeCallBar();
    if (!callBar.parentNode) document.body.appendChild(callBar);
    var right = innerWidth, top = 8;
    var icons = document.querySelectorAll('svg[aria-label]');
    for (var i = 0; i < icons.length; i++) {
      if (callBar.contains(icons[i])) continue;
      var r = icons[i].getBoundingClientRect();
      if (r.width && r.top >= 0 && r.bottom < 72 && r.left > innerWidth / 2 && r.left < right) {
        right = r.left - 8;
        top = r.top + r.height / 2 - 20;
      }
    }
    callBar.style.right = Math.max(0, innerWidth - right) + 'px';
    callBar.style.top = Math.max(0, top) + 'px';
  }

  // ---- Stories row on the Following feed -------------------------------------------------
  // Instagram's Following page has no stories row (only its For-you page does), so the filter
  // draws one at the top of the feed, as in the app: unseen stories first, in Instagram's own
  // ranking, with the gradient ring (green for close friends) and a grey ring once seen. The
  // list comes from the same stories-tray API the site uses; a tap opens Instagram's own story
  // viewer in place. If the list can't be loaded, there's simply no row.
  var appId = null;
  var xhrHeader = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
    if (!appId && /^x-ig-app-id$/i.test(name)) appId = String(value);
    return xhrHeader.apply(this, arguments);
  };
  var trayEl = null;
  var trayItems = null;
  var trayFetchedAt = 0;
  var trayLoading = false;
  var TRAY_TTL = 60 * 1000;
  function myUserId() {
    var m = /(?:^|;\s*)ds_user_id=(\d+)/.exec(document.cookie);
    return m ? m[1] : null;
  }
  function fetchTray(force) {
    if (trayLoading || (!force && Date.now() - trayFetchedAt < TRAY_TTL)) return;
    trayLoading = true;
    var headers = { 'X-Requested-With': 'XMLHttpRequest' };
    if (appId) headers['X-IG-App-ID'] = appId;
    (origFetch || window.fetch).call(window, '/api/v1/feed/reels_tray/', { credentials: 'include', headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) {
        trayLoading = false;
        trayFetchedAt = Date.now();
        if (!j || !j.tray) return;
        var me = myUserId();
        var mine = null, unseen = [], seen = [];
        for (var i = 0; i < j.tray.length; i++) {
          var t = j.tray[i];
          var u = t && t.user;
          if (!u || !u.username || t.muted || t.ad_expiry_timestamp_in_millis) continue;
          var item = {
            id: String(t.id || u.pk || u.id),
            username: u.username,
            pic: u.profile_pic_url,
            unseen: (t.latest_reel_media || 0) > (t.seen || 0),
            besties: (t.latest_besties_reel_media || 0) > (t.seen || 0)
          };
          if (me && String(u.pk || u.id) === me) mine = item;
          else (item.unseen ? unseen : seen).push(item);
        }
        trayItems = { mine: mine, others: unseen.concat(seen) };
        renderTray();
      })
      .catch(function () { trayLoading = false; trayFetchedAt = Date.now(); });
  }
  // Opening someone's stories by address shows only theirs: when they run out, the viewer
  // leaves for the feed. So the row works as in the app, that exit is turned into the next
  // person's stories (unseen ones if you tapped an unseen one, otherwise everyone after the
  // one tapped), with the stories page swapped in place so Back still returns to the feed.
  // Leaving on purpose (the close button, a swipe down, Back) still leaves.
  var storyChain = null;
  var userClosedAt = 0;
  function storyPath(username) {
    return '/stories/' + encodeURIComponent(username) + '/';
  }
  function storyUser(path) {
    var m = /^\/stories\/([^/]+)\/?/.exec(path || '');
    if (!m) return null;
    try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; }
  }
  function storyTrace(msg) {
    log('stories:', msg);
    try {
      var t = JSON.parse(sessionStorage.getItem('rb-story-log') || '[]');
      t.push(new Date().toISOString().slice(11, 19) + ' ' + msg);
      sessionStorage.setItem('rb-story-log', JSON.stringify(t.slice(-30)));
    } catch (e) {}
  }
  function openStory(item, list) {
    var people = list.filter(function (it) { return !item.unseen || it.unseen; });
    var at = people.indexOf(item);
    storyChain = people.length > 1 ? people : null;
    storyTrace('open ' + (at + 1) + '/' + people.length);
    history.pushState(null, '', storyPath(item.username));
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }
  function nextStoryInstead(url, how) {
    if (!storyChain) return false;
    if (how) storyTrace('viewer exit via ' + how);
    var cur = storyUser(location.pathname);
    if (!cur) return false;
    var to;
    try { to = new URL(url == null ? location.href : String(url), location.href); } catch (e) { return false; }
    if (to.origin !== location.origin || storyUser(to.pathname)) return false;
    // Only the viewer's own exit to the feed; a tap on a name opens that profile as normal.
    if (to.pathname !== '/') { storyTrace('left for ' + to.pathname.split('/')[1]); storyChain = null; return false; }
    // Already moving on (the viewer may ask to leave more than once).
    if (swapping) return true;
    if (Date.now() - userClosedAt < 1000) { storyTrace('closed'); storyChain = null; return false; }
    // A tap on the left of someone's first story: back to the person before (as in the app),
    // or the viewer stays put if there's no one before.
    if (Date.now() - leftTapAt < 800) return goStory(-1) || true;
    if (!goStory(1)) { storyTrace('end of row'); storyChain = null; return false; }
    return true;
  }
  // Moves to the next (1) or previous (-1) person in the row; false if there's no one there.
  function goStory(dir) {
    if (swapping) return true;
    var at = chainIndex(storyUser(location.pathname));
    var to = at >= 0 ? storyChain[at + dir] : null;
    if (!to) return false;
    storyTrace((dir > 0 ? 'next ' : 'previous ') + (at + dir + 1) + '/' + storyChain.length);
    swapStory(to, dir);
    return true;
  }
  function chainIndex(username) {
    for (var i = 0; storyChain && i < storyChain.length; i++) if (storyChain[i].username === username) return i;
    return -1;
  }

  // Between people the viewer slides away and the next one slides in, as in the app. The
  // next one comes in once its first story is on screen (black while waiting, never the feed).
  var swapping = false;
  // The photos and videos filling the viewer, by address.
  function storyMedia() {
    var out = [];
    var els = document.querySelectorAll('body video, body img');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.offsetWidth < innerWidth * 0.6) continue;
      var ready = el.tagName === 'VIDEO' ? el.readyState >= 2 : el.complete && el.naturalWidth > 0;
      out.push({ src: el.currentSrc || el.src, ready: ready });
    }
    return out;
  }
  // The next person's viewer is up: a photo or video that wasn't on screen before has loaded.
  function storyShown(oldSrcs) {
    if (storyUser(location.pathname) == null) return false;
    var media = storyMedia();
    for (var i = 0; i < media.length; i++) if (media[i].ready && media[i].src && oldSrcs.indexOf(media[i].src) < 0) return true;
    return false;
  }
  function swapStory(next, dir) {
    var root = document.documentElement;
    swapping = true;
    var started = Date.now();
    var oldSrcs = storyMedia().map(function (m) { return m.src; });
    root.classList.toggle('rb-swap-back', dir < 0);
    root.classList.add('rb-swap', 'rb-swap-out');
    // The next person starts loading straight away, while the old one slides out. The router
    // is told after its own call has finished, so the address it settles on is the new one;
    // the stories page is swapped in place, so Back still returns to the feed.
    setTimeout(function () {
      history.replaceState(null, '', storyPath(next.username));
      dispatchEvent(new PopStateEvent('popstate', { state: null }));
    }, 0);
    setTimeout(function () {
      root.classList.remove('rb-swap-out');
      root.classList.add('rb-swap-wait');
      (function ready() {
        var shown = storyShown(oldSrcs);
        if (!shown && Date.now() - started < 2500) { setTimeout(ready, 30); return; }
        storyTrace(shown ? 'slid in after ' + (Date.now() - started) + 'ms' : 'slid in before loading');
        // Two frames for the first story to be drawn before it slides in.
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            root.classList.remove('rb-swap-wait');
            root.classList.add('rb-swap-in');
            setTimeout(function () {
              root.classList.remove('rb-swap', 'rb-swap-in', 'rb-swap-back');
              swapping = false;
            }, 320);
          });
        });
      })();
    }, 170);
  }
  // The page's whole app (on the stories page, the viewer) is what moves.
  css.push('html.rb-swap, html.rb-swap body { background: #000 !important; overflow: hidden !important; }');
  css.push('html.rb-swap body > div { transition: transform .17s ease-in, opacity .17s ease-in; will-change: transform; }');
  css.push('html.rb-swap-out body > div { transform: translateX(-35%) scale(.9); opacity: 0; }');
  css.push('html.rb-swap-wait body > div { transition: none; transform: translateX(100%); opacity: 1; }');
  css.push('html.rb-swap-in body > div { transition: transform .28s cubic-bezier(.2, .8, .2, 1); transform: none; }');
  // Going back a person, the slides run the other way.
  css.push('html.rb-swap-back.rb-swap-out body > div { transform: translateX(35%) scale(.9); }');
  css.push('html.rb-swap-back.rb-swap-wait body > div { transform: translateX(-100%); }');
  var leftTapAt = 0;
  function inChain() {
    return !!storyChain && /^\/stories\//.test(location.pathname);
  }
  function markUserClose(e) {
    if (!inChain()) return;
    var t = e.target;
    if (t && t.closest && t.closest('svg[aria-label="Close"], [aria-label="Close"]')) userClosedAt = Date.now();
    else if (t && t.querySelector && t.querySelector(':scope > svg[aria-label="Close"]')) userClosedAt = Date.now();
  }
  document.addEventListener('pointerdown', markUserClose, true);
  document.addEventListener('click', markUserClose, true);
  // Swipes in the viewer: down closes it, left and right move between people, as in the app.
  var touchY = null, touchX = 0, touchAt = 0;
  document.addEventListener('touchstart', function (e) {
    var t = e.touches[0];
    touchY = t ? t.clientY : null;
    touchX = t ? t.clientX : 0;
    touchAt = Date.now();
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function (e) {
    var t = e.touches[0];
    if (!t || touchY == null || !inChain()) return;
    var dy = t.clientY - touchY;
    if (dy > 60 && dy > Math.abs(t.clientX - touchX)) userClosedAt = Date.now();
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function (e) {
    var t = e.changedTouches[0];
    if (!t || touchY == null || !inChain()) return;
    var dx = t.clientX - touchX, dy = t.clientY - touchY;
    if (Math.abs(dx) > 60 && Math.abs(dx) > 2 * Math.abs(dy) && Date.now() - touchAt < 800) {
      goStory(dx < 0 ? 1 : -1);
    } else if (Math.abs(dx) < 12 && Math.abs(dy) < 12 && t.clientX < innerWidth * 0.3) {
      leftTapAt = Date.now();
    }
  }, { capture: true, passive: true });
  // Back (the system button) leaves too; it arrives as a popstate, which is never redirected.
  window.addEventListener('popstate', function () {
    if (storyChain && !storyUser(location.pathname)) { storyTrace('back to ' + location.pathname); storyChain = null; }
  });
  function trayTile(label, pic, ring, onTap, plus) {
    var tile = document.createElement('div');
    tile.className = 'rb-tile';
    tile.setAttribute('role', 'button');
    tile.setAttribute('tabindex', '0');
    tile.setAttribute('aria-label', label === 'Your story' ? label : 'Story by ' + label);
    var r = document.createElement('div');
    r.className = 'rb-ring ' + ring;
    var img = document.createElement('img');
    img.alt = '';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.referrerPolicy = 'origin-when-cross-origin';
    if (pic) img.src = pic;
    r.appendChild(img);
    if (plus) {
      var p = document.createElement('span');
      p.className = 'rb-plus';
      p.textContent = '+';
      r.appendChild(p);
    }
    var name = document.createElement('span');
    name.className = 'rb-name';
    name.textContent = label;
    tile.appendChild(r);
    tile.appendChild(name);
    tile.addEventListener('click', onTap);
    return tile;
  }
  function myPic() {
    var img = document.querySelector('[data-rb-tabs] img');
    return img ? img.src : null;
  }
  function renderTray() {
    if (!trayItems) return;
    if (!trayEl) {
      trayEl = document.createElement('div');
      trayEl.id = 'rb-tray';
    }
    while (trayEl.firstChild) trayEl.removeChild(trayEl.firstChild);
    var mine = trayItems.mine;
    // Your story: yours to watch if you have one; otherwise Instagram's own "new" button.
    trayEl.appendChild(trayTile('Your story', (mine && mine.pic) || myPic(), mine ? (mine.unseen ? 'rb-new' : 'rb-seen') : 'rb-none', function () {
      if (mine) { openStory(mine, [mine]); return; }
      var add = document.querySelector('svg[aria-label="New post"], svg[aria-label="New Post"], svg[aria-label="Create"]');
      var btn = add && add.closest('a, button, [role="button"], [role="link"]');
      if (btn) btn.click();
    }, !mine));
    for (var i = 0; i < trayItems.others.length; i++) {
      (function (it) {
        trayEl.appendChild(trayTile(it.username, it.pic, it.besties ? 'rb-besties' : it.unseen ? 'rb-new' : 'rb-seen', function () { openStory(it, trayItems.others); }));
      })(trayItems.others[i]);
    }
    placeTray();
  }
  // The row goes right above the feed list (inside the page's own column, so it scrolls with
  // the posts). Instagram may rebuild that column; the row is put back when it does.
  function placeTray() {
    if (!onFollowingFeed()) {
      if (trayEl && trayEl.parentNode) trayEl.parentNode.removeChild(trayEl);
      return;
    }
    if (!trayItems) { fetchTray(false); return; }
    var art = document.querySelector('main article');
    var list = art && art.parentElement;
    if (!list || !list.parentElement || !trayEl) return;
    if (trayEl.parentNode !== list.parentElement || trayEl.nextSibling !== list) list.parentElement.insertBefore(trayEl, list);
    // Your own picture comes from the bottom bar, which may load after the row is drawn.
    var mineImg = !trayItems.mine && trayEl.querySelector('.rb-tile img');
    if (mineImg && !mineImg.getAttribute('src')) {
      var pic = myPic();
      if (pic) mineImg.src = pic;
    }
  }
  // Home has no back arrow in the app: the Following page's arrow goes (its title, in the same link, stays).
  css.push('html.rb-following a[href="/"] svg[aria-label="Back"] { display: none !important; }');
  css.push('#rb-tray { display: flex; gap: 14px; overflow-x: auto; padding: 10px 12px 12px; border-bottom: 1px solid rgb(var(--ig-separator, 38, 38, 38)); scrollbar-width: none; -webkit-overflow-scrolling: touch; }');
  css.push('#rb-tray::-webkit-scrollbar { display: none; }');
  css.push('#rb-tray .rb-tile { flex: 0 0 auto; width: 68px; display: flex; flex-direction: column; align-items: center; gap: 4px; cursor: pointer; -webkit-tap-highlight-color: transparent; }');
  css.push('#rb-tray .rb-ring { position: relative; width: 66px; height: 66px; border-radius: 50%; padding: 3px; box-sizing: border-box; }');
  css.push('#rb-tray .rb-ring img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; display: block; border: 2px solid rgb(var(--ig-primary-background, 0, 0, 0)); box-sizing: border-box; background: rgb(var(--ig-secondary-background, 38, 38, 38)); }');
  css.push('#rb-tray .rb-new { background: linear-gradient(45deg, #feda75, #fa7e1e, #d62976, #962fbf, #4f5bd5); }');
  css.push('#rb-tray .rb-besties { background: #1cd14f; }');
  css.push('#rb-tray .rb-seen { background: rgb(var(--ig-elevated-separator, 54, 54, 54)); padding: 2px; }');
  css.push('#rb-tray .rb-none { padding: 0; }');
  css.push('#rb-tray .rb-plus { position: absolute; right: 0; bottom: 0; width: 22px; height: 22px; border-radius: 50%; background: rgb(var(--ig-primary-button, 0, 149, 246)); color: #fff; font-size: 18px; line-height: 19px; text-align: center; border: 2px solid rgb(var(--ig-primary-background, 0, 0, 0)); box-sizing: border-box; }');
  css.push('#rb-tray .rb-name { max-width: 68px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; line-height: 16px; color: rgb(var(--ig-primary-text, 245, 245, 245)); }');

  // ---- Camera button in a chat, as in the app ------------------------------------------
  // The app's message bar starts with a blue camera button: tap, take a photo, send. The
  // website only has a gallery icon, so the button is added, and it uses the chat's own photo
  // picker, asking for the camera (the app opens the camera straight away for that). The
  // photo then goes through Instagram's usual attach-and-send.
  var CAMERA_ICON = [['path', { d: 'M4 8.5A2.5 2.5 0 0 1 6.5 6h1.6l1.2-1.8A1.5 1.5 0 0 1 10.55 3.5h2.9a1.5 1.5 0 0 1 1.25.7L15.9 6h1.6A2.5 2.5 0 0 1 20 8.5v8a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5z' }], ['circle', { cx: '12', cy: '12.3', r: '3.4' }]];
  var cameraBtn = null;
  function chatPhotoInput() {
    var inputs = document.querySelectorAll('input[type="file"]');
    for (var i = 0; i < inputs.length; i++) {
      var acc = (inputs[i].getAttribute('accept') || '').toLowerCase();
      if (!acc || /image|\.jpe?g|\.png/.test(acc)) return inputs[i];
    }
    return null;
  }
  function takeChatPhoto() {
    var input = chatPhotoInput();
    if (!input) { log('no photo picker in this chat'); return; }
    var had = input.getAttribute('capture');
    input.setAttribute('capture', 'environment');
    input.click();
    // Instagram's own gallery icon keeps opening the gallery.
    setTimeout(function () { if (had == null) input.removeAttribute('capture'); else input.setAttribute('capture', had); }, 0);
  }
  function makeCameraBtn() {
    var b = document.createElement('button');
    b.type = 'button';
    b.id = 'rb-camera';
    b.setAttribute('aria-label', 'Camera');
    var svg = icon(CAMERA_ICON);
    svg.setAttribute('width', '20');
    svg.setAttribute('height', '20');
    b.appendChild(svg);
    // Pressing it mustn't take focus from the message box (that would drop the keyboard).
    b.addEventListener('mousedown', function (e) { e.preventDefault(); });
    b.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); takeChatPhoto(); });
    return b;
  }
  // The message bar: the smallest element holding both the message box and the gallery icon.
  function placeCameraBtn() {
    if (!threadId()) {
      if (cameraBtn && cameraBtn.parentNode) cameraBtn.parentNode.removeChild(cameraBtn);
      return;
    }
    var box = document.querySelector('[role="textbox"][contenteditable="true"]');
    var gallery = document.querySelector('svg[aria-label="Add Photo or Video"]');
    if (!box || !gallery || !chatPhotoInput()) return;
    var bar = box.parentElement;
    while (bar && !bar.contains(gallery)) bar = bar.parentElement;
    if (!bar) return;
    if (!cameraBtn) cameraBtn = makeCameraBtn();
    if (cameraBtn.parentNode !== bar || bar.firstChild !== cameraBtn) bar.insertBefore(cameraBtn, bar.firstChild);
  }
  css.push('#rb-camera { flex: 0 0 auto; align-self: center; width: 32px; height: 32px; margin: 0 6px; padding: 0; border: 0; border-radius: 50%; background: rgb(var(--ig-primary-button, 0, 149, 246)); color: #fff; display: flex; align-items: center; justify-content: center; cursor: pointer; -webkit-tap-highlight-color: transparent; }');
  css.push('#rb-camera svg { display: block; }');

  // ---- Emoji at the size the app draws them ---------------------------------------------
  // The website draws emoji (the phone's own, Samsung's here) about 1.08x the text size,
  // noticeably bigger than the app. Emoji characters get a font of their own at 75%: the
  // same emoji font, scaled, under the names that start Instagram's font lists, so it's
  // picked for emoji and nothing else (other characters aren't in its range).
  var EMOJI_RANGE = 'U+200D, U+231A-231B, U+23E9-23FA, U+2600-27BF, U+2B50, U+2B55, U+FE0F, U+1F000-1FAFF, U+E0020-E007F';
  var EMOJI_SRC = 'src: local("SamsungColorEmoji"), local("NotoColorEmoji"), local("Noto Color Emoji"); unicode-range: ' + EMOJI_RANGE + '; font-weight: 100 900; font-display: block;';
  // Android only: on iPhone, Safari's emoji are already the system's, and "-apple-system"
  // there is Safari's own font name, which mustn't be redefined.
  if (cfg.app) {
    ['-apple-system', 'Optimistic', 'Instagram Sans UI', 'Instagram Sans Regular'].forEach(function (name) {
      css.push('@font-face { font-family: "' + name + '"; ' + EMOJI_SRC + ' size-adjust: 75%; }');
    });
    // What you're typing keeps emoji at their normal size, as the keyboard shows them: the
    // message and comment boxes use the unscaled emoji first.
    css.push('@font-face { font-family: "rb-emoji-typing"; ' + EMOJI_SRC + ' }');
    css.push('[contenteditable="true"], [contenteditable="true"] *, textarea, input { font-family: "rb-emoji-typing", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif !important; }');
  }

  // ---- Stories: an ad story is hidden and passed straight away ---------------------------
  // The website marks a story ad with "Ad" (or "Sponsored") right after the account name at
  // the top. While one shows, the page is made fully transparent (the viewer behind is black)
  // and the ad's cards are tapped past, the way the right side of a story is tapped to
  // advance. The taps are bounded, and never land on a link or button (an ad's "Learn more"
  // or a link sticker); if no safe spot is found the ad is shown and left to the user.
  var STORY_AD_LABEL = /^(sponsored|ad)$/i;
  var MAX_STORY_TAPS = 4;
  var storyTaps = 0;
  var lastStoryTap = 0;
  function storyHeader() {
    var out = [];
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = walker.nextNode()) && out.length < 4) {
      var t = n.nodeValue.trim();
      if (!t || !n.parentElement) continue;
      var r = n.parentElement.getBoundingClientRect();
      if (r.height > 0 && r.top >= 0 && r.top < 220) out.push(t);
    }
    return out;
  }
  function isStoryAd() {
    // [account name, label, ...]: the label is one of the first header texts.
    var head = storyHeader();
    return head.length >= 2 && (STORY_AD_LABEL.test(head[1]) || STORY_AD_LABEL.test(head[2] || ''));
  }
  function safeTapTarget() {
    var points = [[0.85, 0.5], [0.92, 0.4], [0.92, 0.6], [0.8, 0.35]];
    for (var i = 0; i < points.length; i++) {
      var x = innerWidth * points[i][0], y = innerHeight * points[i][1];
      var el = document.elementFromPoint(x, y);
      if (el && !el.closest('a, button, input, textarea, [role="link"], [role="button"][aria-label]')) return { el: el, x: x, y: y };
    }
    return null;
  }
  function tapNextStory() {
    var t = safeTapTarget();
    if (!t) return false;
    var o = { bubbles: true, cancelable: true, clientX: t.x, clientY: t.y, pointerId: 1, isPrimary: true, pointerType: 'touch' };
    t.el.dispatchEvent(new PointerEvent('pointerdown', o));
    t.el.dispatchEvent(new MouseEvent('mousedown', o));
    t.el.dispatchEvent(new PointerEvent('pointerup', o));
    t.el.dispatchEvent(new MouseEvent('mouseup', o));
    t.el.dispatchEvent(new MouseEvent('click', o));
    return true;
  }
  function checkStory() {
    var root = document.documentElement;
    if (!document.body) return;
    if (!cfg.skipStoryAds || !root.classList.contains('rb-stories')) {
      root.classList.remove('rb-story-ad');
      storyTaps = 0;
      return;
    }
    if (!isStoryAd()) {
      root.classList.remove('rb-story-ad');
      storyTaps = 0;
      return;
    }
    if (storyTaps >= MAX_STORY_TAPS) {
      // Couldn't get past it: show it rather than leave the user on a black screen.
      root.classList.remove('rb-story-ad');
      return;
    }
    root.classList.add('rb-story-ad');
    var now = Date.now();
    if (now - lastStoryTap < 150) return;
    lastStoryTap = now;
    storyTaps++;
    if (!tapNextStory()) {
      storyTaps = MAX_STORY_TAPS;
      root.classList.remove('rb-story-ad');
      return;
    }
    log('skipping story ad');
    // A static ad card may not change the page again until it's passed: look once more.
    setTimeout(checkStory, 200);
  }
  // Opacity, not visibility: hidden elements can't receive the tap that passes the ad.
  css.push('html.rb-story-ad body { opacity: 0 !important; }');
  var style = document.getElementById('rb-style');
  if (style) style.textContent = css.join('\n');

  // ---- Observer: every added node is checked before it's painted ------------------------
  var pending = false;
  var navTries = 0;
  var lastCallBarPlace = 0;
  function scan(root) {
    if (!root || !root.querySelectorAll) return;
    if (root.tagName === 'ARTICLE') checkArticle(root);
    var arts = root.querySelectorAll('article');
    for (var i = 0; i < arts.length; i++) checkArticle(arts[i]);
    var spans = root.querySelectorAll('span, h2, h3, div[role="heading"], button, a, div[role="button"]');
    for (var j = 0; j < spans.length && j < 400; j++) {
      var el = spans[j];
      if (el.childElementCount > 1) continue;
      if (cfg.hideSuggested && el.childElementCount === 0) checkSection(el);
      checkAppPrompt(el);
    }
  }
  var observer = new MutationObserver(function (mutations) {
    onRoute();
    for (var i = 0; i < mutations.length; i++) {
      var m = mutations[i];
      for (var k = 0; k < m.addedNodes.length; k++) {
        var node = m.addedNodes[k];
        if (node.nodeType === 1) {
          scan(node);
          // Text that changes inside an existing post (lazy headers) re-checks that post.
          var art = node.closest && node.closest('article');
          if (art) checkArticle(art);
        } else if (node.nodeType === 3 && node.parentElement) {
          var a = node.parentElement.closest('article');
          if (a) checkArticle(a);
        }
      }
    }
    if (!pending) {
      pending = true;
      // Stories change in place; one check per frame is enough.
      requestAnimationFrame(function () {
        pending = false;
        checkStory();
        if (navTries < 600 && !document.querySelector('[data-rb-nav] [data-rb-tabs]')) { navTries++; markNav(); }
        setCaught();
        patchCallModules();
        if (threadId() && Date.now() - lastCallBarPlace > 500) { lastCallBarPlace = Date.now(); placeCallBar(); placeCameraBtn(); }
        if (cfg.followingFeed) placeTray();
      });
    }
  });
  function start() {
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    onRoute();
    scan(document.documentElement);
  }
  whenRoot(start);
})();
