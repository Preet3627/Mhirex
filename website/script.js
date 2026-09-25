/**
 * Mhirex Landing Page Interactive Scripts
 * Provides screenshot tab switcher, 3D tilt, scroll animations,
 * navbar behavior, multilingual support (EN, GU, HI),
 * and automatic GitHub Release APK direct download updater.
 */

document.addEventListener('DOMContentLoaded', () => {
  // Current language state
  let currentLang = localStorage.getItem('mhirex_lang') || 'en';
  let activeTabNumber = '1';

  // 1. Language Switching Functionality
  function applyLanguage(lang) {
    if (!translations[lang]) return;
    currentLang = lang;
    localStorage.setItem('mhirex_lang', lang);

    document.documentElement.lang = lang;
    document.body.classList.remove('lang-gu', 'lang-hi');
    if (lang === 'gu') document.body.classList.add('lang-gu');
    if (lang === 'hi') document.body.classList.add('lang-hi');

    // Update active state in switcher
    document.querySelectorAll('.lang-btn').forEach(btn => {
      if (btn.getAttribute('data-lang') === lang) {
        btn.classList.add('active');
      } else {
        btn.classList.remove('active');
      }
    });

    // Translate all elements with data-i18n
    document.querySelectorAll('[data-i18n]').forEach(el => {
      const key = el.getAttribute('data-i18n');
      const text = translations[lang][key];
      if (text !== undefined) {
        if (text.includes('<') && text.includes('>')) {
          el.innerHTML = text;
        } else {
          el.textContent = text;
        }
      }
    });

    // Translate attributes like title
    document.querySelectorAll('[data-i18n-title]').forEach(el => {
      const key = el.getAttribute('data-i18n-title');
      const text = translations[lang][key];
      if (text !== undefined) {
        el.title = text;
      }
    });

    // Refresh showcase tab content
    updateShowcaseContent(activeTabNumber);
  }

  // Setup language switcher click handlers
  document.querySelectorAll('.lang-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const lang = btn.getAttribute('data-lang');
      applyLanguage(lang);
    });
  });

  // 2. Navbar Scroll Effect
  const navbar = document.getElementById('navbar');
  window.addEventListener('scroll', () => {
    if (window.scrollY > 40) {
      navbar.classList.add('scrolled');
    } else {
      navbar.classList.remove('scrolled');
    }
  });

  // 3. Mobile Menu Toggle
  const mobileToggle = document.getElementById('mobileToggle');
  const navMenu = document.getElementById('navMenu');

  // Single source of truth for the dropdown state: the panel slide plus the
  // burger's X. Callers below (link taps, resizes) just say open or closed.
  function setMenuOpen(open) {
    if (!navMenu) return;
    navMenu.classList.toggle('open', open);
    const spans = mobileToggle ? mobileToggle.querySelectorAll('span') : [];
    if (spans.length < 3) return;
    spans[0].style.transform = open ? 'translateY(7px) rotate(45deg)' : 'none';
    spans[1].style.opacity = open ? '0' : '1';
    spans[2].style.transform = open ? 'translateY(-7px) rotate(-45deg)' : 'none';
  }

  if (mobileToggle && navMenu) {
    mobileToggle.addEventListener('click', () => {
      setMenuOpen(!navMenu.classList.contains('open'));
    });

    // Close menu when clicking a link. Delegated on the panel rather than bound
    // per <a>, because the Download and GitHub links are moved in here at
    // <=768px (section 3b) and do not exist as children yet at this point.
    navMenu.addEventListener('click', (e) => {
      if (e.target.closest('a')) {
        setMenuOpen(false);
      }
    });
  }

  // 3b. Relocate the header actions into the dropdown on phones.
  // Brand + language switcher + GitHub + Download + burger needs ~630px, but a
  // 360px bar only has ~320px, and the Hindi/Gujarati labels are wider than the
  // English ones. That overflow is what made the whole page scroll sideways on
  // mobile. Rather than shrink controls until they are unusable (or duplicate
  // the markup), move the existing nodes: they keep their listeners, ids and
  // i18n wiring, and the desktop header is left byte-for-byte identical.
  const navMenuActions = document.getElementById('navMenuActions');
  const navActions = document.querySelector('.nav-actions');
  const narrowScreen = window.matchMedia('(max-width: 768px)');

  if (navMenuActions && navActions && mobileToggle) {
    // Order matters: these are re-inserted in the same sequence before the
    // burger, which is exactly their original order in the bar.
    const relocatable = [
      document.getElementById('langSwitcher'),
      navActions.querySelector('.nav-github'),
      navActions.querySelector('.nav-download')
    ].filter(Boolean);

    function placeNavActions(inMenu) {
      relocatable.forEach(el => {
        if (inMenu) {
          navMenuActions.appendChild(el);
        } else {
          navActions.insertBefore(el, mobileToggle);
        }
      });
    }

    function syncNavActions() {
      placeNavActions(narrowScreen.matches);
      // A menu left open across a rotate/resize back to desktop would render
      // off-canvas, so close it whenever the actions leave the dropdown.
      if (!narrowScreen.matches) {
        setMenuOpen(false);
      }
    }

    syncNavActions();

    // `addEventListener` on the MediaQueryList works on every target browser;
    // the deprecated addListener/removeListener pair is not needed.
    narrowScreen.addEventListener('change', syncNavActions);
  }

  // 4. Interactive Screenshots Showcase Switcher
  const screenTabs = document.querySelectorAll('.screen-tab');
  const showcaseImg = document.getElementById('showcaseImage');
  const showcaseTitle = document.getElementById('showcaseTitle');
  const showcaseDesc = document.getElementById('showcaseDesc');

  function updateShowcaseContent(tabNum) {
    activeTabNumber = tabNum;
    const titleKey = `sc_title_${tabNum}`;
    const descKey = `sc_desc_${tabNum}`;

    if (showcaseTitle && translations[currentLang] && translations[currentLang][titleKey]) {
      showcaseTitle.textContent = translations[currentLang][titleKey];
    }
    if (showcaseDesc && translations[currentLang] && translations[currentLang][descKey]) {
      showcaseDesc.textContent = translations[currentLang][descKey];
    }
  }

  screenTabs.forEach(tab => {
    tab.addEventListener('click', () => {
      screenTabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');

      const imgSrc = tab.getAttribute('data-img');
      const tabNum = tab.getAttribute('data-tab') || '1';

      if (showcaseImg) {
        showcaseImg.style.opacity = '0';
        setTimeout(() => {
          showcaseImg.src = imgSrc;
          showcaseImg.style.opacity = '1';
        }, 150);
      }

      updateShowcaseContent(tabNum);
    });
  });

  // 5. Subtle 3D Tilt Effect for Cards
  const tiltCards = document.querySelectorAll('.bento-card, .founder-spotlight-card, #heroDevice');

  tiltCards.forEach(card => {
    card.addEventListener('mousemove', (e) => {
      const rect = card.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      
      const centerX = rect.width / 2;
      const centerY = rect.height / 2;
      
      const rotateX = ((y - centerY) / centerY) * -4;
      const rotateY = ((x - centerX) / centerX) * 4;

      card.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) translateY(-2px)`;

      const glow = card.querySelector('.bento-glow');
      if (glow) {
        glow.style.top = `${y - 90}px`;
        glow.style.left = `${x - 90}px`;
      }
    });

    card.addEventListener('mouseleave', () => {
      card.style.transform = 'perspective(1000px) rotateX(0deg) rotateY(0deg) translateY(0)';
    });
  });

  // 6. Scroll Reveal with Intersection Observer
  const observerOptions = {
    threshold: 0.1,
    rootMargin: '0px 0px -50px 0px'
  };

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('fade-in-up');
        observer.unobserve(entry.target);
      }
    });
  }, observerOptions);

  document.querySelectorAll('.bento-card, .roadmap-card, .founder-spotlight-card, .comparison-table').forEach(el => {
    observer.observe(el);
  });

  // 7. Release label updater (cosmetic only - the download hrefs are /api/apk)
  //
  // This asks our own /api/release rather than api.github.com. It used to call
  // GitHub straight from the visitor's phone, which meant the version could not
  // be shown at all on a rate limit (unauthenticated GitHub is 60 req/hr per IP
  // and carriers NAT many phones behind one address), offline, or behind a
  // privacy blocker, and in those cases it silently kept a hardcoded string, so
  // the page quietly advertised a stale version. /api/release resolves the same
  // release server-side, on the same edge cache and the same selection rule as
  // the download, so the version shown and the APK served cannot disagree.
  async function updateDownloadLinks() {
    try {
      const resp = await fetch('/api/release');
      if (!resp.ok) return;
      const data = await resp.json();

      // ok:false means our endpoint reached GitHub and failed. The markup
      // already holds the last known value, so there is nothing to correct and
      // nothing worth shouting about.
      if (!data || !data.ok || !data.tag) return;

      document.querySelectorAll('.js-release-version, .badge-version').forEach(el => {
        el.textContent = data.tag;
      });

      if (data.sizeMb) {
        document.querySelectorAll('.js-apk-size').forEach(el => {
          el.textContent = `~${data.sizeMb} MB`;
        });
      }

      // The hrefs are deliberately NOT rewritten here. They point at /api/apk,
      // which resolves the newest release server-side, so nothing on this page
      // can send a visitor to a stale APK.
    } catch (e) {
      console.warn('Release label fetch failed; showing the pre-JS fallback values:', e);
    }
  }

  // Resolve the current version from the server, not from GitHub in the browser
  updateDownloadLinks();

  // Initialize with saved or default language
  applyLanguage(currentLang);
});
