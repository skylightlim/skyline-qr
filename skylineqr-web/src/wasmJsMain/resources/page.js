// The page side of the website: sizing the canvases, pointer events, the theme, and drawing the
// flat code for the fallback. Main.kt drives it.
'use strict';

const SkylinePage = (() => {
  const POSTER_MIN_MILLIS = 1200;
  const stage = document.getElementById('stage');
  const canvases = [document.getElementById('city'), document.getElementById('flat')];
  const resizers = [];

  // Device pixels, but no more than two to a CSS pixel: 4x MSAA on a 3x phone screen costs more
  // than it shows.
  function size() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.max(1, Math.round(stage.clientWidth * dpr));
    const h = Math.max(1, Math.round(stage.clientHeight * dpr));
    for (const c of canvases) {
      c.width = w;
      c.height = h;
    }
  }
  size();
  new ResizeObserver(() => {
    size();
    for (const f of resizers) f();
  }).observe(stage);

  function onResize(f) {
    resizers.push(f);
  }

  const DOWN = 0, MOVE = 1, UP = 2, CANCEL = 3;

  function onPointer(id, f) {
    const el = document.getElementById(id);
    const at = (e) => {
      const r = el.getBoundingClientRect();
      return [e.clientX - r.left, e.clientY - r.top];
    };
    el.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;
      el.setPointerCapture(e.pointerId);
      const [x, y] = at(e);
      f(DOWN, e.pointerId, x, y);
    });
    el.addEventListener('pointermove', (e) => {
      const [x, y] = at(e);
      f(MOVE, e.pointerId, x, y);
    });
    el.addEventListener('pointerup', (e) => {
      const [x, y] = at(e);
      f(UP, e.pointerId, x, y);
    });
    el.addEventListener('pointercancel', (e) => {
      const [x, y] = at(e);
      f(CANCEL, e.pointerId, x, y);
    });
    el.addEventListener('contextmenu', (e) => e.preventDefault());
    // Enter or space on the focused city folds it, as a tap does.
    el.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      e.preventDefault();
      f(DOWN, -1, 0, 0);
      f(UP, -1, 0, 0);
    });
  }

  function onWheel(id, f) {
    document.getElementById(id).addEventListener(
      'wheel',
      (e) => {
        e.preventDefault();
        f(e.deltaMode === 1 ? e.deltaY * 16 : e.deltaY);
      },
      { passive: false },
    );
  }

  function setTheme(color, light) {
    document.querySelector('meta[name="theme-color"]').setAttribute('content', color);
    document.documentElement.style.setProperty('--sky', color);
    document.documentElement.dataset.sky = light ? 'light' : 'dark';
  }

  // The flat code: a mosaic of square modules on the hour's paper, with a four-module quiet zone.
  let code = { n: 0, paper: '#fff', colors: [] };

  function codeBegin(n, paper) {
    code = { n, paper, colors: new Array(n * n) };
  }

  function codeModule(col, row, color) {
    code.colors[row * code.n + col] = color;
  }

  function codeDraw(canvasId, quiet) {
    const n = code.n;
    const side = n + 2 * quiet;
    const canvas = document.getElementById(canvasId);
    const cell = (Math.min(canvas.width, canvas.height) * 0.9) / side;
    const ox = (canvas.width - cell * side) / 2;
    const oy = (canvas.height - cell * side) / 2;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = code.paper;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    for (let row = 0; row < n; row++) {
      for (let col = 0; col < n; col++) {
        ctx.fillStyle = code.colors[row * n + col];
        // A half-pixel overlap hides seams between modules.
        ctx.fillRect(ox + (col + quiet) * cell, oy + (row + quiet) * cell, cell + 0.5, cell + 0.5);
      }
    }
  }

  // The loading poster stays until the first frame of the city has drawn, or the flat code has
  // taken its place, and for at least a moment so it never just flickers past.
  const posterShownAt = performance.now();
  let posterGone = false;
  function loaded() {
    if (posterGone) return;
    posterGone = true;
    const poster = document.getElementById('poster');
    if (!poster) return;
    setTimeout(() => {
      poster.classList.add('gone');
      poster.addEventListener('transitionend', () => poster.remove(), { once: true });
      // Reduced motion has no transition to end.
      setTimeout(() => poster.remove(), 1000);
    }, Math.max(0, POSTER_MIN_MILLIS - (performance.now() - posterShownAt)));
  }

  return { onResize, onPointer, onWheel, setTheme, codeBegin, codeModule, codeDraw, loaded };
})();
globalThis.SkylinePage = SkylinePage;

// If the page's Kotlin never starts (a browser without WebAssembly GC, or a failed download), say so
// rather than leave an empty sky: the Kotlin side fills the link box as soon as it runs.
setTimeout(() => {
  const input = document.getElementById('link-input');
  const notice = document.getElementById('notice');
  if (!input || input.value || !notice) return;
  notice.textContent = "This browser can't run Skyline QR. Open the page in a recent Chrome, Edge, Safari or Firefox.";
  notice.hidden = false;
  SkylinePage.loaded();
}, 10000);
