(function () {
  'use strict';

  var LANG_KEY = 'hermes_lang';
  var THEME_KEY = 'hermes-site-theme';
  var PAGE_LANG = (document.documentElement.getAttribute('lang') || 'en').toLowerCase().indexOf('fr') === 0 ? 'fr' : 'en';

  function detectTheme() {
    var attr = document.documentElement.getAttribute('data-theme');
    if (attr === 'light' || attr === 'dark') return attr;
    try {
      var params = new URLSearchParams(window.location.search);
      var q = params.get('theme');
      if (q === 'light' || q === 'dark') return q;
      var saved = window.localStorage.getItem(THEME_KEY);
      if (saved === 'light' || saved === 'dark') return saved;
    } catch (e) {}
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  var currentTheme = detectTheme();
  var stageModeOverride = null;
  var galleryModeOverride = null;

  function syncThemeImages(theme) {
    document.querySelectorAll('img[data-src-light][data-src-dark]').forEach(function (img) {
      if (img.hasAttribute('data-fixed-theme')) return;
      var target = theme === 'dark' ? img.getAttribute('data-src-dark') : img.getAttribute('data-src-light');
      if (target && img.getAttribute('src') !== target) {
        img.setAttribute('src', target);
      }
    });
  }

  function applyTheme(theme) {
    currentTheme = theme === 'dark' ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', currentTheme);
    try { window.localStorage.setItem(THEME_KEY, currentTheme); } catch (e) {}
    document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
      var label = currentTheme === 'dark'
        ? (PAGE_LANG === 'fr' ? 'Clair' : 'Light')
        : (PAGE_LANG === 'fr' ? 'Sombre' : 'Dark');
      btn.textContent = label;
      btn.setAttribute('aria-pressed', currentTheme === 'dark' ? 'true' : 'false');
    });
    syncThemeImages(currentTheme);
    if (stageModeOverride !== 'both') {
      updateShowcase(currentTheme);
    }
    if (galleryModeOverride !== 'both') {
      applyGalleryMode(currentTheme, false);
    }
  }

  function initLangSwitchers() {
    document.querySelectorAll('[data-lang-switch]').forEach(function (link) {
      link.addEventListener('click', function () {
        var target = link.getAttribute('data-lang-switch');
        if (target === 'fr' || target === 'en') {
          try { window.localStorage.setItem(LANG_KEY, target); } catch (e) {}
        }
      });
    });
  }

  function initMobileMenu() {
    var btn = document.querySelector('[data-menu-toggle]');
    var drawer = document.getElementById('mobile-nav');
    if (!btn || !drawer) return;

    function setOpen(open) {
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      drawer.classList.toggle('is-open', open);
    }

    btn.addEventListener('click', function () {
      var expanded = btn.getAttribute('aria-expanded') === 'true';
      setOpen(!expanded);
    });
    drawer.querySelectorAll('a').forEach(function (a) {
      a.addEventListener('click', function () { setOpen(false); });
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && btn.getAttribute('aria-expanded') === 'true') {
        setOpen(false);
        btn.focus();
      }
    });
    window.addEventListener('resize', function () {
      if (window.innerWidth >= 768) setOpen(false);
    });
  }

  function setSegmentPressed(selector, attr, value) {
    document.querySelectorAll(selector).forEach(function (btn) {
      btn.setAttribute('aria-pressed', btn.getAttribute(attr) === value ? 'true' : 'false');
    });
  }

  function updateShowcase(mode) {
    var active = document.querySelector('.showcase-tab[aria-selected="true"]');
    if (!active) return;
    var effectiveMode = mode || stageModeOverride || currentTheme;
    var titleEl = document.getElementById('showcase-title');
    var descEl = document.getElementById('showcase-desc');
    var badgeEl = document.getElementById('showcase-badge');
    var imgPrimary = document.getElementById('showcase-img');
    var imgSecondary = document.getElementById('showcase-img-dark');
    var phonesWrap = document.getElementById('showcase-phones');
    if (!titleEl || !descEl || !imgPrimary || !phonesWrap) return;

    var srcLight = active.getAttribute('data-src-light') || '';
    var srcDark = active.getAttribute('data-src-dark') || srcLight;
    var title = active.getAttribute('data-title') || '';
    var desc = active.getAttribute('data-desc') || '';
    var badge = active.getAttribute('data-badge') || '';

    if (effectiveMode === 'both') {
      phonesWrap.classList.add('is-both');
      if (srcLight && imgPrimary.getAttribute('src') !== srcLight) imgPrimary.setAttribute('src', srcLight);
      imgPrimary.setAttribute('alt', title + (PAGE_LANG === 'fr' ? ' (thème clair)' : ' (light theme)'));
      if (imgSecondary) {
        if (srcDark && imgSecondary.getAttribute('src') !== srcDark) imgSecondary.setAttribute('src', srcDark);
        imgSecondary.setAttribute('alt', title + (PAGE_LANG === 'fr' ? ' (thème sombre)' : ' (dark theme)'));
      }
    } else {
      phonesWrap.classList.remove('is-both');
      var targetSrc = effectiveMode === 'dark' ? srcDark : srcLight;
      if (targetSrc && imgPrimary.getAttribute('src') !== targetSrc) imgPrimary.setAttribute('src', targetSrc);
      imgPrimary.setAttribute('alt', title);
    }

    titleEl.textContent = title;
    descEl.textContent = desc;
    if (badgeEl) badgeEl.textContent = badge;
    setSegmentPressed('[data-stage-mode]', 'data-stage-mode', effectiveMode);
  }

  function initShowcase() {
    var tabs = document.querySelectorAll('.showcase-tab[data-src-light]');
    if (!tabs.length) return;
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        tabs.forEach(function (t) { t.setAttribute('aria-selected', 'false'); });
        tab.setAttribute('aria-selected', 'true');
        updateShowcase();
      });
    });
    document.querySelectorAll('[data-stage-mode]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        stageModeOverride = btn.getAttribute('data-stage-mode');
        updateShowcase(stageModeOverride);
      });
    });
    updateShowcase(currentTheme);
  }

  function applyGalleryMode(mode, isExplicit) {
    var grid = document.getElementById('gallery-grid');
    if (!grid) return;
    var effectiveMode = mode || currentTheme;
    if (isExplicit) galleryModeOverride = effectiveMode;
    grid.setAttribute('data-gallery-mode', effectiveMode);
    setSegmentPressed('[data-gallery-mode-btn]', 'data-gallery-mode-btn', effectiveMode);

    grid.querySelectorAll('.capture-phone-primary').forEach(function (img) {
      var lightSrc = img.getAttribute('data-src-light');
      var darkSrc = img.getAttribute('data-src-dark');
      var target = effectiveMode === 'dark' ? darkSrc : lightSrc;
      if (target && img.getAttribute('src') !== target) img.setAttribute('src', target);
    });
  }

  function initCopyButtons() {
    var copyLabel = PAGE_LANG === 'fr' ? 'Copier' : 'Copy';
    var copiedLabel = PAGE_LANG === 'fr' ? 'Copié' : 'Copied';
    document.querySelectorAll('.code-block').forEach(function (block) {
      if (block.querySelector('.copy-btn')) return;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'copy-btn';
      btn.textContent = copyLabel;
      btn.addEventListener('click', function () {
        var code = block.querySelector('code');
        var text = code ? code.textContent : block.textContent;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(function () {
            btn.textContent = copiedLabel;
            window.setTimeout(function () { btn.textContent = copyLabel; }, 1500);
          });
        }
      });
      block.appendChild(btn);
    });
  }

  function initDocsPortal() {
    var searchInput = document.getElementById('docs-search');
    var status = document.getElementById('docs-search-status');
    var sections = document.querySelectorAll('.doc-section');
    if (searchInput && status && sections.length) {
      searchInput.addEventListener('input', function () {
        var q = searchInput.value.trim().toLowerCase();
        var visible = 0;
        sections.forEach(function (sec) {
          var match = !q || sec.textContent.toLowerCase().indexOf(q) !== -1;
          sec.classList.toggle('is-hidden', !match);
          if (match) visible++;
        });
        if (!q) {
          status.textContent = '';
        } else if (visible === 0) {
          status.textContent = PAGE_LANG === 'fr' ? 'Aucune section trouvée.' : 'No matching sections.';
        } else {
          status.textContent = PAGE_LANG === 'fr'
            ? visible + ' section(s) correspondante(s)'
            : visible + ' matching section(s)';
        }
      });
    }

    var navLinks = document.querySelectorAll('.docs-nav a');
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

    document.querySelectorAll('[data-gallery-mode-btn]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        applyGalleryMode(btn.getAttribute('data-gallery-mode-btn'), true);
      });
    });

    var lightbox = document.getElementById('lightbox');
    var lbImg = document.getElementById('lightbox-img');
    var lbTitle = document.getElementById('lightbox-title');
    var lbClose = document.getElementById('lightbox-close');
    if (!lightbox || !lbImg) return;

    function closeLb() {
      lightbox.classList.remove('is-open');
      lightbox.setAttribute('aria-hidden', 'true');
    }

    document.querySelectorAll('.capture-media[role="button"]').forEach(function (wrap) {
      function openWrap(target) {
        var clickedImg = (target && target.tagName === 'IMG') ? target : wrap.querySelector('img');
        var card = wrap.closest('.capture-card');
        var h3 = card ? card.querySelector('h3') : null;
        if (!clickedImg) return;
        lbImg.setAttribute('src', clickedImg.getAttribute('src'));
        lbImg.setAttribute('alt', clickedImg.getAttribute('alt') || '');
        if (clickedImg.getAttribute('width')) lbImg.setAttribute('width', clickedImg.getAttribute('width'));
        if (clickedImg.getAttribute('height')) lbImg.setAttribute('height', clickedImg.getAttribute('height'));
        if (lbTitle && h3) lbTitle.textContent = h3.textContent;
        lightbox.classList.add('is-open');
        lightbox.setAttribute('aria-hidden', 'false');
        if (lbClose) lbClose.focus();
      }
      wrap.addEventListener('click', function (e) { openWrap(e.target); });
      wrap.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          openWrap(null);
        }
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
        // Update href and metadata without mutating visible button text width (zero CLS).
        a.setAttribute('href', apkUrl);
        a.setAttribute('data-version', tag);
        a.setAttribute('title', (PAGE_LANG === 'fr' ? 'Télécharger l’APK ' : 'Download APK ') + tag);
      });
    }).catch(function () {});
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
      btn.addEventListener('click', function () { applyTheme(currentTheme === 'dark' ? 'light' : 'dark'); });
    });
    initLangSwitchers();
    initMobileMenu();
    initCopyButtons();
    initShowcase();
    initDocsPortal();
    initGallery();
    initLatestRelease();
    applyTheme(currentTheme);
  });
})();
