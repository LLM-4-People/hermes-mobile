(function () {
  'use strict';

  var LANG_KEY = 'hermes-site-lang';
  var THEME_KEY = 'hermes-site-theme';

  function detectLang() {
    try {
      var params = new URLSearchParams(window.location.search);
      var q = params.get('lang');
      if (q === 'fr' || q === 'en') return q;
      var saved = window.localStorage.getItem(LANG_KEY);
      if (saved === 'fr' || saved === 'en') return saved;
    } catch (e) {}
    var nav = (navigator.language || 'fr').toLowerCase();
    return nav.indexOf('fr') === 0 ? 'fr' : 'en';
  }

  function detectTheme() {
    try {
      var params = new URLSearchParams(window.location.search);
      var q = params.get('theme');
      if (q === 'light' || q === 'dark') return q;
      var saved = window.localStorage.getItem(THEME_KEY);
      if (saved === 'light' || saved === 'dark') return saved;
    } catch (e) {}
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  var currentLang = detectLang();
  var currentTheme = detectTheme();

  function applyTheme(theme) {
    currentTheme = theme === 'dark' ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', currentTheme);
    try { window.localStorage.setItem(THEME_KEY, currentTheme); } catch (e) {}
    document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
      var label = currentTheme === 'dark'
        ? (currentLang === 'fr' ? 'Clair' : 'Light')
        : (currentLang === 'fr' ? 'Sombre' : 'Dark');
      btn.textContent = (currentTheme === 'dark' ? '☀ ' : '☾ ') + label;
      btn.setAttribute('aria-pressed', currentTheme === 'dark' ? 'true' : 'false');
    });
  }

  function applyLang(lang) {
    currentLang = lang === 'en' ? 'en' : 'fr';
    document.documentElement.setAttribute('lang', currentLang);
    try { window.localStorage.setItem(LANG_KEY, currentLang); } catch (e) {}

    var titleAttr = document.body.getAttribute('data-title-' + currentLang);
    if (titleAttr) document.title = titleAttr;

    document.querySelectorAll('[data-fr][data-en]').forEach(function (el) {
      var val = el.getAttribute('data-' + currentLang);
      if (val !== null) el.textContent = val;
    });

    document.querySelectorAll('[data-fr-html][data-en-html]').forEach(function (el) {
      var val = el.getAttribute('data-' + currentLang + '-html');
      if (val !== null) el.innerHTML = val;
    });

    document.querySelectorAll('[data-fr-placeholder][data-en-placeholder]').forEach(function (el) {
      el.setAttribute('placeholder', el.getAttribute('data-' + currentLang + '-placeholder'));
    });

    document.querySelectorAll('[data-fr-aria][data-en-aria]').forEach(function (el) {
      el.setAttribute('aria-label', el.getAttribute('data-' + currentLang + '-aria'));
    });

    document.querySelectorAll('[data-fr-alt][data-en-alt]').forEach(function (el) {
      el.setAttribute('alt', el.getAttribute('data-' + currentLang + '-alt'));
    });

    document.querySelectorAll('[data-lang-toggle]').forEach(function (btn) {
      btn.textContent = currentLang === 'fr' ? 'EN' : 'FR';
      btn.setAttribute('aria-label', currentLang === 'fr' ? 'Switch to English' : 'Passer en français');
    });

    applyTheme(currentTheme);
    updateShowcaseCaption();
    updateSearchCount();
  }

  function updateShowcaseCaption() {
    var active = document.querySelector('.showcase-tab[aria-selected="true"]');
    if (!active) return;
    var titleEl = document.getElementById('showcase-title');
    var descEl = document.getElementById('showcase-desc');
    var badgeEl = document.getElementById('showcase-badge');
    var imgEl = document.getElementById('showcase-img');
    var frameEl = document.getElementById('showcase-frame');
    if (!titleEl || !descEl || !imgEl || !frameEl) return;

    var src = active.getAttribute('data-src');
    var kind = active.getAttribute('data-kind') || 'phone';
    var badge = active.getAttribute('data-badge') || '';
    var title = active.getAttribute('data-title-' + currentLang) || '';
    var desc = active.getAttribute('data-desc-' + currentLang) || '';

    if (imgEl.getAttribute('src') !== src) imgEl.setAttribute('src', src);
    imgEl.setAttribute('alt', title);
    frameEl.classList.toggle('is-phone', kind === 'phone');
    frameEl.classList.toggle('is-tablet', kind === 'tablet');
    titleEl.textContent = title;
    descEl.textContent = desc;
    if (badgeEl) badgeEl.textContent = badge;
  }

  function initShowcase() {
    var tabs = document.querySelectorAll('.showcase-tab');
    if (!tabs.length) return;
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        tabs.forEach(function (t) { t.setAttribute('aria-selected', 'false'); });
        tab.setAttribute('aria-selected', 'true');
        updateShowcaseCaption();
      });
    });
    updateShowcaseCaption();
  }

  function initCopyButtons() {
    document.querySelectorAll('.code-block').forEach(function (block) {
      if (block.querySelector('.copy-btn')) return;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'copy-btn';
      btn.setAttribute('data-fr', 'Copier');
      btn.setAttribute('data-en', 'Copy');
      btn.textContent = currentLang === 'fr' ? 'Copier' : 'Copy';
      btn.addEventListener('click', function () {
        var code = block.querySelector('code');
        var text = code ? code.textContent : block.textContent;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(function () {
            btn.textContent = currentLang === 'fr' ? 'Copié' : 'Copied';
            window.setTimeout(function () {
              btn.textContent = currentLang === 'fr' ? 'Copier' : 'Copy';
            }, 1500);
          });
        }
      });
      block.appendChild(btn);
    });
  }

  var searchInputEl = null;
  function updateSearchCount() {
    if (!searchInputEl) return;
    var status = document.getElementById('docs-search-status');
    var sections = document.querySelectorAll('.doc-section');
    if (!status || !sections.length) return;
    var q = searchInputEl.value.trim().toLowerCase();
    var visible = 0;
    sections.forEach(function (sec) {
      var match = !q || sec.textContent.toLowerCase().indexOf(q) !== -1;
      sec.classList.toggle('is-hidden', !match);
      if (match) visible++;
    });
    if (!q) {
      status.textContent = '';
    } else if (visible === 0) {
      status.textContent = currentLang === 'fr' ? 'Aucune section trouvée.' : 'No matching sections.';
    } else {
      status.textContent = currentLang === 'fr'
        ? visible + ' section(s) correspondante(s)'
        : visible + ' matching section(s)';
    }
  }

  function initDocsPortal() {
    searchInputEl = document.getElementById('docs-search');
    if (searchInputEl) {
      searchInputEl.addEventListener('input', updateSearchCount);
    }
    var navLinks = document.querySelectorAll('.docs-nav a');
    var sections = document.querySelectorAll('.doc-section');
    if (!navLinks.length || !sections.length || !('IntersectionObserver' in window)) return;
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          var id = entry.target.getAttribute('id');
          navLinks.forEach(function (a) {
            a.classList.toggle('is-active', a.getAttribute('href') === '#' + id);
          });
        }
      });
    }, { rootMargin: '-20% 0px -65% 0px' });
    sections.forEach(function (s) { observer.observe(s); });
  }

  function initGallery() {
    var filters = document.querySelectorAll('[data-gallery-filter]');
    var cards = document.querySelectorAll('.capture-card');
    if (filters.length && cards.length) {
      filters.forEach(function (btn) {
        btn.addEventListener('click', function () {
          var group = btn.getAttribute('data-gallery-filter');
          filters.forEach(function (b) { b.setAttribute('aria-selected', b === btn ? 'true' : 'false'); });
          cards.forEach(function (c) {
            var cat = c.getAttribute('data-category');
            c.classList.toggle('is-hidden', group !== 'all' && cat !== group);
          });
        });
      });
    }

    var lightbox = document.getElementById('lightbox');
    var lbImg = document.getElementById('lightbox-img');
    var lbTitle = document.getElementById('lightbox-title');
    var lbClose = document.getElementById('lightbox-close');
    if (!lightbox || !lbImg) return;

    function closeLb() {
      lightbox.classList.remove('is-open');
      lightbox.setAttribute('aria-hidden', 'true');
    }

    document.querySelectorAll('.capture-media').forEach(function (wrap) {
      wrap.addEventListener('click', function () {
        var img = wrap.querySelector('img');
        var h3 = wrap.closest('.capture-card').querySelector('h3');
        if (!img) return;
        lbImg.setAttribute('src', img.getAttribute('src'));
        lbImg.setAttribute('alt', img.getAttribute('alt') || '');
        if (lbTitle && h3) lbTitle.textContent = h3.textContent;
        lightbox.classList.add('is-open');
        lightbox.setAttribute('aria-hidden', 'false');
        if (lbClose) lbClose.focus();
      });
    });

    if (lbClose) lbClose.addEventListener('click', closeLb);
    lightbox.addEventListener('click', function (e) { if (e.target === lightbox) closeLb(); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeLb(); });
  }

  function initLatestRelease() {
    var links = document.querySelectorAll('[data-latest-apk]');
    if (!links.length || !window.fetch) return;
    if (window.location.hostname === '127.0.0.1' || window.location.hostname === 'localhost') return;
    window.fetch('https://api.github.com/repos/LLM-4-People/hermes-mobile/releases/latest', {
      headers: { 'Accept': 'application/vnd.github+json' }
    }).then(function (res) {
      if (!res.ok) return null;
      return res.json();
    }).then(function (release) {
      if (!release || !release.tag_name) return;
      var apkUrl = release.html_url || 'https://github.com/LLM-4-People/hermes-mobile/releases/latest';
      if (Array.isArray(release.assets)) {
        for (var i = 0; i < release.assets.length; i++) {
          var asset = release.assets[i];
          if (asset && typeof asset.name === 'string' && /\.apk$/i.test(asset.name) && asset.browser_download_url) {
            apkUrl = asset.browser_download_url;
            break;
          }
        }
      }
      var tag = String(release.tag_name).replace(/[^A-Za-z0-9._+-]/g, '');
      links.forEach(function (a) {
        a.setAttribute('href', apkUrl);
        a.setAttribute('data-fr', 'Télécharger l’APK (' + tag + ')');
        a.setAttribute('data-en', 'Download APK (' + tag + ')');
        a.textContent = currentLang === 'fr' ? 'Télécharger l’APK (' + tag + ')' : 'Download APK (' + tag + ')';
      });
    }).catch(function () {
      // Static fallback URL remains active.
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-lang-toggle]').forEach(function (btn) {
      btn.addEventListener('click', function () { applyLang(currentLang === 'fr' ? 'en' : 'fr'); });
    });
    document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
      btn.addEventListener('click', function () { applyTheme(currentTheme === 'dark' ? 'light' : 'dark'); });
    });
    initCopyButtons();
    initShowcase();
    initDocsPortal();
    initGallery();
    initLatestRelease();
    applyLang(currentLang);
  });
})();
