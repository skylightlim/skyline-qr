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

  // The postcard (2026-11-22), as the app's ui/Postcard.kt draws it: 1080 by 1350, the city as the
  // camera sees it on the code's paper, the link, the place, hour and date beneath, and the code
  // in the corner at whole pixels a module so it scans. Keep the two in step.
  const CARD_W = 1080;
  const CARD_H = 1350;
  const MARGIN = 48;
  const GAP = 36;
  const CODE_SIDE = 340;
  const MIN_MODULE_PX = 3;

  function postcardLayout(modules, quiet) {
    const side = modules + 2 * quiet;
    const modulePx = Math.max(MIN_MODULE_PX, Math.floor(CODE_SIDE / side));
    const codeSide = side * modulePx;
    const quietPx = quiet * modulePx;
    const codeRight = Math.min(CARD_W, CARD_W - MARGIN + quietPx);
    const codeBottom = Math.min(CARD_H, CARD_H - MARGIN + quietPx);
    const code = { left: codeRight - codeSide, top: codeBottom - codeSide, side: codeSide };
    const photoBottom = Math.min(code.top, code.top + quietPx - GAP);
    const photo = { left: MARGIN, top: MARGIN, width: CARD_W - 2 * MARGIN, height: photoBottom - MARGIN };
    const wordsTop = photoBottom + GAP;
    const wordsRight = Math.min(code.left, code.left + quietPx - GAP);
    const words = { left: MARGIN, top: wordsTop, width: wordsRight - MARGIN, bottom: CARD_H - MARGIN };
    return { photo, words, code, modulePx };
  }

  function rgbOf(css) {
    const m = /rgb\((\d+),(\d+),(\d+)\)/.exec(css.replace(/\s/g, ''));
    return m ? [+m[1], +m[2], +m[3]] : [0, 0, 0];
  }

  /** [rgb] scaled toward black by [keep], or mixed over [over] when given. */
  function darken(rgb, keep, over) {
    const c = over ? rgb.map((v, i) => over[i] * (1 - keep) + v * keep) : rgb.map((v) => v * keep);
    return `rgb(${c.map(Math.round).join(',')})`;
  }

  function drawCode(ctx, left, top, px, quiet, paper = code.paper) {
    const n = code.n;
    ctx.fillStyle = paper;
    ctx.fillRect(left, top, (n + 2 * quiet) * px, (n + 2 * quiet) * px);
    for (let row = 0; row < n; row++) {
      for (let col = 0; col < n; col++) {
        ctx.fillStyle = code.colors[row * n + col];
        ctx.fillRect(left + (col + quiet) * px, top + (row + quiet) * px, px, px);
      }
    }
  }

  /** [text] cut with an ellipsis to fit [width] in the context's font. */
  function fit(ctx, text, width) {
    if (ctx.measureText(text).width <= width) return text;
    let lo = 0;
    let hi = text.length;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (ctx.measureText(text.slice(0, mid) + '…').width <= width) lo = mid;
      else hi = mid - 1;
    }
    return text.slice(0, lo) + '…';
  }

  /** At most [max] lines of [text] within [width], the last cut with an ellipsis. */
  function wrap(ctx, text, width, max) {
    const lines = [];
    let rest = text;
    while (rest && lines.length < max) {
      if (lines.length === max - 1 || ctx.measureText(rest).width <= width) {
        lines.push(fit(ctx, rest, width));
        break;
      }
      // Break at the last space, slash or dot that fits, else wherever it stops fitting.
      let end = 1;
      while (end < rest.length && ctx.measureText(rest.slice(0, end + 1)).width <= width) end++;
      const cut = Math.max(rest.lastIndexOf(' ', end), rest.lastIndexOf('/', end - 1) + 1, rest.lastIndexOf('.', end - 1) + 1);
      const at = cut > end / 2 ? cut : end;
      lines.push(rest.slice(0, at).trimEnd());
      rest = rest.slice(at).trimStart();
    }
    return lines;
  }

  /**
   * The postcard: the city in the middle of the photo's frame, on the paper (the photo has no sky,
   * and its own background colour becomes the card's, so there is no edge), the link and "Kuala
   * Lumpur · the date" beneath, and the code in the corner. Without a photo, the code alone, large.
   */
  function drawPostcard(photo, quiet, title, caption, inkCss) {
    const layout = postcardLayout(code.n, quiet);
    const ink = rgbOf(inkCss);
    const card = document.createElement('canvas');
    card.width = CARD_W;
    card.height = CARD_H;
    const ctx = card.getContext('2d');
    // The photo's own paper, as the composite left it, so the photo has no visible edge.
    let paper = code.paper;
    if (photo) {
      const corner = photo.getContext('2d').getImageData(1, 1, 1, 1).data;
      paper = `rgb(${corner[0]},${corner[1]},${corner[2]})`;
    }
    ctx.fillStyle = paper;
    ctx.fillRect(0, 0, CARD_W, CARD_H);

    const f = layout.photo;
    if (photo) {
      // The photo is drawn at the frame's shape; scale it whole into the frame.
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(photo, f.left, f.top, f.width, f.height);
    } else {
      // No city here (no WebGPU): the code alone, as big as fits, whole pixels a module.
      const side = code.n + 2 * quiet;
      const px = Math.max(1, Math.floor((Math.min(f.width, f.height) * 0.86) / side));
      drawCode(ctx, f.left + Math.floor((f.width - side * px) / 2), f.top + Math.floor((f.height - side * px) / 2), px, quiet, paper);
    }

    // Its quiet zone on the card's own paper, so the code sits on the card as the city does.
    drawCode(ctx, layout.code.left, layout.code.top, layout.modulePx, quiet, paper);

    // The link, then the place and the day, level with the foot of the code.
    const w = layout.words;
    const sans = "'Inter', system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans', 'Noto Sans CJK SC', sans-serif";
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = darken(ink, 0.55);
    ctx.font = `500 34px ${sans}`;
    const captionBase = w.bottom - 4;
    ctx.fillText(fit(ctx, caption, w.width), w.left, captionBase);
    ctx.fillStyle = darken(ink, 0.28);
    ctx.font = `700 60px ${sans}`;
    const lines = wrap(ctx, title, w.width, 2);
    lines.forEach((line, i) => ctx.fillText(line, w.left, captionBase - 58 - (lines.length - 1 - i) * 70));
    return card;
  }

  let postcardUrl = null;
  let postcardFile = null;

  /**
   * Makes the postcard from the code last given (codeBegin, codeModule) and, [withPhoto], a frame
   * of the city drawn for it (startPhoto, at the photo's size; endPhoto puts the screen back; none
   * without WebGPU), then shows it with Share and Save.
   */
  async function postcard(title, caption, inkCss, quiet, withPhoto, startPhoto, endPhoto) {
    const button = document.getElementById('postcard');
    if (button.classList.contains('busy')) return;
    button.classList.add('busy');
    document.querySelector('details.more')?.removeAttribute('open');
    try {
      const date = new Date().toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
      let shot = Promise.resolve(null);
      if (withPhoto) {
        // Drawn at 1.5 times the frame, for a crisp city once it is scaled into the card.
        const f = postcardLayout(code.n, quiet).photo;
        startPhoto(Math.round(f.width * 1.5), Math.round(f.height * 1.5));
        shot = SkylineGpu.snapshot().finally(() => endPhoto());
      }
      const [photo] = await Promise.all([
        shot,
        document.fonts ? Promise.all(['700 60px Inter', '500 34px Inter'].map((f) => document.fonts.load(f))).catch(() => {}) : null,
      ]);
      const card = drawPostcard(photo, quiet, title, `${caption} · ${date}`, inkCss);
      const blob = await new Promise((resolve, reject) => card.toBlob((b) => (b ? resolve(b) : reject(new Error('no image'))), 'image/png'));
      if (postcardUrl) URL.revokeObjectURL(postcardUrl);
      postcardUrl = URL.createObjectURL(blob);
      postcardFile = new File([blob], 'skyline-qr-postcard.png', { type: 'image/png' });
      document.getElementById('postcard-image').src = postcardUrl;
      // Share where the browser can share files; elsewhere Save does it.
      const canShare = !!(navigator.canShare && navigator.canShare({ files: [postcardFile] }));
      document.getElementById('postcard-share').hidden = !canShare;
      document.getElementById('postcard-dialog').showModal();
    } catch (e) {
      console.warn('SkylineQR postcard:', e);
      const error = document.getElementById('error');
      error.textContent = "Couldn't take the postcard's photo. Try again in a moment.";
      setTimeout(() => { if (error.textContent.startsWith("Couldn't take")) error.textContent = ''; }, 4000);
    } finally {
      button.classList.remove('busy');
    }
  }

  {
    const dialog = document.getElementById('postcard-dialog');
    document.getElementById('postcard-close').addEventListener('click', () => dialog.close());
    // A tap on the dimmed page round the postcard closes it too.
    dialog.addEventListener('click', (e) => {
      const r = dialog.getBoundingClientRect();
      const outside = e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
      if (e.target === dialog && outside) dialog.close();
    });
    const save = () => {
      if (!postcardUrl) return;
      const a = document.createElement('a');
      a.href = postcardUrl;
      a.download = 'skyline-qr-postcard.png';
      document.body.append(a);
      a.click();
      a.remove();
    };
    document.getElementById('postcard-save').addEventListener('click', save);
    // Share opens the system's share sheet; where the browser refuses (no file sharing, or no
    // longer counting the tap), the postcard is saved instead, so the button always does something.
    document.getElementById('postcard-share').addEventListener('click', async () => {
      if (!postcardFile) return;
      try {
        if (!(navigator.canShare && navigator.canShare({ files: [postcardFile] }))) throw new Error('no file sharing');
        await navigator.share({ files: [postcardFile] });
      } catch (e) {
        if (e.name === 'AbortError') return;
        console.warn('SkylineQR share:', e);
        save();
      }
    });
  }

  return { onResize, onPointer, onWheel, setTheme, codeBegin, codeModule, codeDraw, loaded, postcard };
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
