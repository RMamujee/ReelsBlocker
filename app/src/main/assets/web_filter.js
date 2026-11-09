// Reels Blocker's filter for Instagram's website, injected before Instagram's own scripts.
// Like a browser ad blocker it changes the page itself: unwanted things are removed as they
// are added, before they are drawn, so nothing is covered or scrolled past.
//
// window.__rbConfig is set by FeedActivity from the switches on the main screen.
(function () {
  'use strict';
  if (window.__rbFilter) return;
  window.__rbFilter = true;

  var cfg = window.__rbConfig || {};
  var log = cfg.debug ? function () { console.log.apply(console, ['[rb]'].concat([].slice.call(arguments))); } : function () {};

  var AD_LABELS = /^(sponsored|ad|paid partnership)$/i;
  var SUGGESTED_LABELS = /^(suggested for you|suggestions for you|suggested posts|suggested reels|suggested for you to follow)$/i;
  // Instagram's prompts to switch to the app.
  var APP_PROMPT = /^(use the app|open instagram|get the app|open the app|see more in the app|switch to the app|use the instagram app)$/i;
  var FOLLOW_LABELS = /^(follow|follow back)$/i;
  var HIDDEN = 'data-rb-hidden';

  // ---- Styles: things hidden purely by CSS ----------------------------------------------
  var css = [
    '[' + HIDDEN + '] { display: none !important; }',
    '[data-rb-feed] { padding-bottom: 0 !important; }',
    '[data-rb-feed]::after { content: "Only posts from people you follow are shown"; display: block; padding: 24px 16px 8px; text-align: center; font-size: 13px; color: #8e8e8e; }',
    // "Open the app" banners and prompts.
    'a[href*="/download/"], a[href^="intent:"], a[href*="itunes.apple.com"], a[href*="play.google.com"] { display: none !important; }'
  ];
  if (cfg.blockReels) {
    // The Reels tab and any link to the Reels feed.
    css.push('a[href="/reels/"], a[href^="/reels/"] { display: none !important; }');
  }
  if (cfg.hideExplore) {
    // On the Explore page the grid tiles are links to posts and reels; search results are
    // links to profiles, tags and places, so they stay.
    css.push('html.rb-explore main a[href*="/p/"], html.rb-explore main a[href*="/reel/"] { display: none !important; }');
    css.push('html.rb-explore main [role="progressbar"] { display: none !important; }');
  }
  function addStyle() {
    var style = document.createElement('style');
    style.id = 'rb-style';
    style.textContent = css.join('\n');
    (document.head || document.documentElement).appendChild(style);
  }
  if (!document.getElementById('rb-style')) addStyle();

  // ---- Routes: Reels pages never open, the Explore class follows the URL ----------------
  var lastPath = '';
  function onRoute() {
    var path = location.pathname;
    if (path === lastPath) return;
    lastPath = path;
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
    checkStory();
  }
  function wrap(name) {
    var orig = history[name];
    history[name] = function () {
      var r = orig.apply(this, arguments);
      onRoute();
      return r;
    };
  }
  wrap('pushState');
  wrap('replaceState');
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
    for (var i = 0; i < head.length; i++) {
      if (cfg.hideAds && AD_LABELS.test(head[i])) return 'ad';
      if (cfg.hideSuggested && (SUGGESTED_LABELS.test(head[i]) || FOLLOW_LABELS.test(head[i]))) return 'suggested';
    }
    return null;
  }
  function checkArticle(article) {
    if (article.hasAttribute(HIDDEN)) return;
    var reason = postReason(article);
    if (reason) {
      article.setAttribute(HIDDEN, reason);
      // Instagram's feed is a virtual list that pads its bottom with the space it expects
      // the posts below to take, counting removed ones, which leaves blank screens. The list
      // ends at its last real post instead, with a note under it.
      if (article.parentElement) article.parentElement.setAttribute('data-rb-feed', '');
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
  function markNav() {
    var link = document.querySelector('a[href="/explore/"]');
    for (var e = link && link.parentElement; e && e !== document.body; e = e.parentElement) {
      if (getComputedStyle(e).position === 'fixed') { e.setAttribute('data-rb-nav', ''); return; }
    }
  }

  // ---- Stories: an ad story is hidden and passed straight away ---------------------------
  // The website marks a story ad with "Ad" next to the account name at the top. While one
  // shows, the page is made fully transparent (the viewer behind is black) and every card of
  // the ad is tapped past at once, the way the right side of a story is tapped to advance.
  var lastStoryTap = 0;
  function storyHeader() {
    var out = [];
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = walker.nextNode()) && out.length < 12) {
      var t = n.nodeValue.trim();
      if (!t || !n.parentElement) continue;
      var r = n.parentElement.getBoundingClientRect();
      if (r.height > 0 && r.top >= 0 && r.top < 220) out.push(t);
    }
    return out;
  }
  function tapNextStory() {
    var x = innerWidth * 0.85, y = innerHeight * 0.5;
    var el = document.elementFromPoint(x, y);
    if (!el) return;
    var o = { bubbles: true, cancelable: true, clientX: x, clientY: y, pointerId: 1, isPrimary: true, pointerType: 'touch' };
    el.dispatchEvent(new PointerEvent('pointerdown', o));
    el.dispatchEvent(new MouseEvent('mousedown', o));
    el.dispatchEvent(new PointerEvent('pointerup', o));
    el.dispatchEvent(new MouseEvent('mouseup', o));
    el.dispatchEvent(new MouseEvent('click', o));
  }
  function checkStory() {
    var root = document.documentElement;
    if (!document.body) return;
    if (!cfg.skipStoryAds || !root.classList.contains('rb-stories')) {
      root.classList.remove('rb-story-ad');
      return;
    }
    var isAd = storyHeader().some(function (t) { return AD_LABELS.test(t); });
    root.classList.toggle('rb-story-ad', isAd);
    if (!isAd) return;
    var now = Date.now();
    if (now - lastStoryTap < 150) return;
    lastStoryTap = now;
    log('skipping story ad');
    tapNextStory();
    // A static ad card may not change the page again until it's passed: look once more.
    setTimeout(checkStory, 200);
  }
  // Opacity, not visibility: hidden elements can't receive the tap that passes the ad.
  css.push('html.rb-story-ad body { opacity: 0 !important; }');
  var style = document.getElementById('rb-style');
  if (style) style.textContent = css.join('\n');

  // ---- Observer: every added node is checked before it's painted ------------------------
  var pending = false;
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
        if (!document.querySelector('[data-rb-nav]')) markNav();
      });
    }
  });
  function start() {
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    onRoute();
    scan(document.documentElement);
  }
  if (document.documentElement) start(); else document.addEventListener('DOMContentLoaded', start);
})();
