/*
 * Photo manager: one grid of photos, used on the tablet AND on the phone page.
 *
 *   Tap a photo          -> select it (tap again to un-select)
 *   Hold, then drag      -> move it somewhere else
 *   Buttons at the bottom -> Earlier / Later / Delete, when something is selected
 *
 * The order is saved on the tablet (POST /api/order), so the slideshow follows it.
 * Written in plain, older JavaScript so it also runs on the tablet's old web view.
 *
 * Usage:  var pm = PhotoManager.mount(element, { scroller, onShow, onChange, toast });
 *         pm.refresh();
 */
(function () {
  var CSS = [
    '.pm { position: relative; -webkit-user-select: none; user-select: none; }',
    '.pm-hint { color: var(--dim, #9ba1ad); font-size: 16px; margin: 0 0 14px; line-height: 1.4; }',
    '.pm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(var(--pm-min, 130px), 1fr)); grid-gap: 10px;',
    '  padding-bottom: 120px; }',
    '.pm-tile { position: relative; height: 0; padding-top: 100%; border-radius: 16px; overflow: hidden;',
    '  background: var(--card, #22252d); -webkit-touch-callout: none; }',
    '.pm-tile img { position: absolute; top: 0; left: 0; width: 100%; height: 100%; object-fit: cover; display: block;',
    '  pointer-events: none; }',
    '.pm-num { position: absolute; left: 8px; bottom: 8px; min-width: 26px; height: 26px; padding: 0 7px; border-radius: 13px;',
    '  background: rgba(13,14,18,.72); color: #fff; font-size: 14px; font-weight: 700; line-height: 26px; text-align: center; }',
    '.pm-check { position: absolute; right: 8px; top: 8px; width: 28px; height: 28px; border-radius: 14px;',
    '  border: 2px solid rgba(255,255,255,.85); background: rgba(13,14,18,.35); color: transparent; font-size: 17px;',
    '  font-weight: 800; line-height: 24px; text-align: center; }',
    '.pm-tile.sel::after { content: ""; position: absolute; top: 0; left: 0; right: 0; bottom: 0; border-radius: 16px;',
    '  border: 4px solid #fff; pointer-events: none; }',
    '.pm-tile.sel img { opacity: .72; }',
    '.pm-tile.sel .pm-check { background: #fff; border-color: #fff; color: #14161b; }',
    '.pm-tile.pm-lift { opacity: .25; }',
    '.pm-ghost { position: fixed !important; z-index: 9999; pointer-events: none; padding-top: 0 !important;',
    '  transform: scale(1.06); opacity: .95; }',
    '.pm-empty { display: none; text-align: center; color: var(--dim, #9ba1ad); font-size: 19px; padding: 60px 10px; }',
    '.pm-bar { position: fixed; left: 50%; bottom: 20px; transform: translate(-50%, 200%); z-index: 60;',
    '  width: 640px; max-width: calc(100% - 24px); background: #2a2d35; border-radius: 28px; padding: 12px;',
    '  transition: transform .22s ease; }',
    '.pm-bar.show { transform: translate(-50%, 0); }',
    '.pm-count { display: block; text-align: center; color: var(--dim, #9ba1ad); font-size: 15px; font-weight: 600; margin-bottom: 10px; }',
    '.pm-row { display: flex; }',
    '.pm-row button { flex: 1; height: 50px; border: 0; border-radius: 999px; background: var(--card, #22252d);',
    '  color: var(--text, #f5f5f6); font-size: 15px; font-weight: 700; padding: 0 4px; font-family: inherit; }',
    '.pm-row button + button { margin-left: 8px; }',
    '.pm-row button:active { background: var(--card2, #2c3039); }',
    '.pm-row .pm-danger { color: #fff; }',
    '.pm-row .pm-danger.confirm { background: #fff; color: #14161b; }',
    '.pm-row .pm-ghostbtn { flex: none; width: 50px; }'
  ].join('\n');

  function injectCss() {
    if (document.getElementById('pm-css')) return;
    var s = document.createElement('style');
    s.id = 'pm-css';
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  function api(method, url, body, cb) {
    var x = new XMLHttpRequest();
    x.open(method, url);
    x.onload = function () {
      var j = null;
      try { j = JSON.parse(x.responseText); } catch (e) {}
      cb(x.status, j);
    };
    x.onerror = function () { cb(0, null); };
    x.send(body == null ? null : body);
  }

  function mount(root, opts) {
    opts = opts || {};
    injectCss();
    if (opts.min) root.style.setProperty('--pm-min', opts.min + 'px');
    var scroller = opts.scroller || document.scrollingElement || document.documentElement;
    var say = opts.toast || function () {};

    var order = [];
    var selected = {};
    var confirmTimer = null;
    var suppressClick = false;

    root.classList.add('pm');
    root.innerHTML =
      '<div class="pm-hint">Tap photos to select them. Hold a photo, then drag it to move it.</div>' +
      '<div class="pm-grid"></div>' +
      '<div class="pm-empty">No photos yet.</div>' +
      '<div class="pm-bar"><span class="pm-count"></span><div class="pm-row">' +
      '<button data-act="earlier">&#9664; Earlier</button>' +
      '<button data-act="later">Later &#9654;</button>' +
      '<button data-act="show">Show now</button>' +
      '<button data-act="del" class="pm-danger">Delete</button>' +
      '<button data-act="clear" class="pm-ghostbtn">&#10005;</button>' +
      '</div></div>';
    var grid = root.querySelector('.pm-grid');
    var empty = root.querySelector('.pm-empty');
    var bar = root.querySelector('.pm-bar');
    var countEl = root.querySelector('.pm-count');
    var delBtn = bar.querySelector('[data-act=del]');
    var showBtn = bar.querySelector('[data-act=show]');

    // ---------- helpers ----------
    function chosen() { return order.filter(function (n) { return selected[n]; }); }

    function saveOrder() {
      api('POST', '/api/order', JSON.stringify(order), function (code) {
        if (code !== 200) say('Could not save the new order');
      });
      if (opts.onChange) opts.onChange(order.slice());
    }

    function resetDelete() {
      clearTimeout(confirmTimer);
      delBtn.classList.remove('confirm');
      var n = chosen().length;
      delBtn.textContent = n > 1 ? 'Delete ' + n : 'Delete';
    }

    function updateBar() {
      var n = chosen().length;
      bar.classList.toggle('show', n > 0);
      countEl.textContent = n + (n === 1 ? ' photo selected' : ' photos selected');
      showBtn.style.display = (opts.onShow && n === 1) ? '' : 'none';
      resetDelete();
    }

    function renumber() {
      var tiles = grid.children;
      for (var i = 0; i < tiles.length; i++) tiles[i].querySelector('.pm-num').textContent = String(i + 1);
    }

    function render() {
      grid.innerHTML = '';
      empty.style.display = order.length ? 'none' : 'block';
      order.forEach(function (name, i) {
        var t = document.createElement('div');
        t.className = 'pm-tile' + (selected[name] ? ' sel' : '');
        t.setAttribute('data-name', name);
        t.innerHTML = '<img alt="" draggable="false"><span class="pm-num"></span><span class="pm-check">&#10003;</span>';
        t.querySelector('img').src = '/thumb/' + encodeURIComponent(name);
        t.querySelector('.pm-num').textContent = String(i + 1);
        grid.appendChild(t);
      });
      updateBar();
    }

    function load(cb) {
      api('GET', '/api/photos', null, function (code, list) {
        if (code === 200 && list) {
          order = list;
          var keep = {};
          order.forEach(function (n) { if (selected[n]) keep[n] = true; });
          selected = keep;
          render();
        }
        if (cb) cb();
      });
    }

    // ---------- tap to select ----------
    function tileOf(el) { return el && el.closest ? el.closest('.pm-tile') : null; }

    grid.addEventListener('click', function (e) {
      if (suppressClick) return;
      var t = tileOf(e.target);
      if (!t) return;
      var name = t.getAttribute('data-name');
      if (selected[name]) delete selected[name]; else selected[name] = true;
      t.classList.toggle('sel', !!selected[name]);
      updateBar();
    });
    grid.addEventListener('contextmenu', function (e) { e.preventDefault(); });

    // ---------- buttons ----------
    function move(dir) {
      var n = order.length, i;
      if (dir < 0) {
        for (i = 1; i < n; i++) {
          if (selected[order[i]] && !selected[order[i - 1]]) { var a = order[i]; order[i] = order[i - 1]; order[i - 1] = a; }
        }
      } else {
        for (i = n - 2; i >= 0; i--) {
          if (selected[order[i]] && !selected[order[i + 1]]) { var b = order[i]; order[i] = order[i + 1]; order[i + 1] = b; }
        }
      }
      render();
      saveOrder();
      // keep the first selected photo in view
      var first = grid.querySelector('.pm-tile.sel');
      if (first && first.scrollIntoView) first.scrollIntoView({ block: 'nearest' });
    }

    function doDelete() {
      var names = chosen();
      if (!names.length) return;
      if (!delBtn.classList.contains('confirm')) {
        delBtn.classList.add('confirm');
        delBtn.textContent = 'Tap again to delete ' + (names.length > 1 ? names.length : '');
        clearTimeout(confirmTimer);
        confirmTimer = setTimeout(resetDelete, 3500);
        return;
      }
      resetDelete();
      api('POST', '/api/delete-many', JSON.stringify(names), function (code, j) {
        if (code !== 200) { say('Could not delete'); return; }
        order = order.filter(function (n) { return !selected[n]; });
        selected = {};
        render();
        say(names.length === 1 ? 'Photo deleted' : names.length + ' photos deleted');
        if (opts.onChange) opts.onChange(order.slice());
      });
    }

    bar.addEventListener('click', function (e) {
      var b = e.target.closest ? e.target.closest('button') : null;
      if (!b) return;
      var act = b.getAttribute('data-act');
      if (act === 'earlier') move(-1);
      else if (act === 'later') move(1);
      else if (act === 'del') doDelete();
      else if (act === 'clear') { selected = {}; render(); }
      else if (act === 'show') { var c = chosen(); if (c.length === 1 && opts.onShow) opts.onShow(c[0]); }
    });

    // ---------- hold and drag to rearrange ----------
    var press = null, pressTimer = null, drag = null;

    grid.addEventListener('touchstart', function (e) {
      if (e.touches.length !== 1) return;
      var t = tileOf(e.target);
      if (!t) return;
      var p = e.touches[0];
      press = { tile: t, x: p.clientX, y: p.clientY };
      clearTimeout(pressTimer);
      pressTimer = setTimeout(function () { if (press) startDrag(press.tile, press.x, press.y); }, 380);
    }, { passive: true });

    grid.addEventListener('touchmove', function (e) {
      var p = e.touches[0];
      if (drag) {
        e.preventDefault();
        dragMove(p.clientX, p.clientY);
      } else if (press && (Math.abs(p.clientX - press.x) > 10 || Math.abs(p.clientY - press.y) > 10)) {
        clearTimeout(pressTimer);
        press = null; // the finger is scrolling, not dragging
      }
    }, { passive: false });

    function endTouch() {
      clearTimeout(pressTimer);
      press = null;
      if (drag) endDrag();
    }
    grid.addEventListener('touchend', endTouch);
    grid.addEventListener('touchcancel', endTouch);

    function startDrag(tile, x, y) {
      var r = tile.getBoundingClientRect();
      var ghost = tile.cloneNode(true);
      ghost.className = 'pm-tile pm-ghost';
      ghost.style.width = r.width + 'px';
      ghost.style.height = r.height + 'px';
      ghost.style.left = r.left + 'px';
      ghost.style.top = r.top + 'px';
      document.body.appendChild(ghost);
      tile.classList.add('pm-lift');
      drag = { tile: tile, ghost: ghost, dx: x - r.left, dy: y - r.top, x: x, y: y, timer: null };
      drag.timer = setInterval(autoScroll, 30);
      if (navigator.vibrate) { try { navigator.vibrate(25); } catch (err) {} }
    }

    function autoScroll() {
      if (!drag) return;
      var h = window.innerHeight;
      if (drag.y < 90) scroller.scrollTop -= 14;
      else if (drag.y > h - 90) scroller.scrollTop += 14;
    }

    function dragMove(x, y) {
      drag.x = x; drag.y = y;
      drag.ghost.style.left = (x - drag.dx) + 'px';
      drag.ghost.style.top = (y - drag.dy) + 'px';
      var under = document.elementFromPoint(x, y);
      var t = tileOf(under);
      if (t && t !== drag.tile && t.parentNode === grid) {
        var kids = Array.prototype.slice.call(grid.children);
        var from = kids.indexOf(drag.tile), to = kids.indexOf(t);
        grid.insertBefore(drag.tile, from > to ? t : t.nextSibling);
        renumber();
      }
    }

    function endDrag() {
      clearInterval(drag.timer);
      if (drag.ghost.parentNode) drag.ghost.parentNode.removeChild(drag.ghost);
      drag.tile.classList.remove('pm-lift');
      drag = null;
      suppressClick = true;
      setTimeout(function () { suppressClick = false; }, 450);
      var next = Array.prototype.map.call(grid.children, function (c) { return c.getAttribute('data-name'); });
      if (next.join('|') !== order.join('|')) {
        order = next;
        renumber();
        saveOrder();
      }
    }

    load();
    return {
      refresh: load,
      clearSelection: function () { selected = {}; render(); }
    };
  }

  window.PhotoManager = { mount: mount };
})();
