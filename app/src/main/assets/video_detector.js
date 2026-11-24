/*
 * DreamWeb — detector de video (contracts/video-detection-bridge.md §4).
 *
 * Modo document-start: WebViewCompat.addDocumentStartJavaScript corre este
 * script antes que los de la página → hooks de HTMLMediaElement activos.
 * Modo fallback (window.__DW_FALLBACK__ === true): evaluado en
 * onPageFinished → los hooks de setters se omiten (los players ya existen)
 * y el sondeo + observer cubren la detección tardía.
 *
 * Reporte: DreamWebSniffer.onVideoFound(JSON.stringify(report)) con
 * url, pageUrl, title, mimeType, width, height, durationSec, positionSec,
 * headers{}, subtitles[], mediaKey. NUNCA document.cookie — CookieManager
 * es la fuente autoritativa en el lanzamiento (contrato §4 nota).
 *
 * Apagado (Principio I): setInterval de players se limpia al primer
 * hallazgo aceptado; backstop setTimeout(120000); observer/intervalos
 * mueren con el documento. Todo envuelto en try/catch — el script jamás
 * lanza hacia la página.
 */
(function () {
  'use strict';
  if (window.__dwDetectorInstalled) return;
  window.__dwDetectorInstalled = true;

  var FALLBACK = window.__DW_FALLBACK__ === true;
  var mediaSeq = 0;
  var elementKeys = typeof WeakMap !== 'undefined' ? new WeakMap() : null;
  var mediaElements = {};      // mediaKey -> <video> (para recaptura de posición)
  var lastElUrl = {};          // mediaKey -> última URL propia reportada (mutaciones)
  var seenSource = {};         // mediaKey -> {url:true} siblings <source> ya reportados
  var foundSomething = false;

  function isHttpMedia(url) {
    return typeof url === 'string' &&
      /^https?:\/\//i.test(url) &&
      url.toLowerCase().indexOf('blob:') !== 0;
  }

  function abs(url) {
    try { return new URL(url, location.href).href; } catch (e) { return url; }
  }

  function mediaKey(el) {
    try {
      if (!el) return null;
      if (elementKeys) {
        var k = elementKeys.get(el);
        if (!k) {
          k = 'v' + (++mediaSeq);
          elementKeys.set(el, k);
          mediaElements[k] = el;
        }
        return k;
      }
      if (!el.__dwKey) el.__dwKey = 'v' + (++mediaSeq);
      mediaElements[el.__dwKey] = el;
      return el.__dwKey;
    } catch (e) { return null; }
  }

  /* Pistas de subtítulos declaradas: <track kind="subtitles|captions">
   * (src, srclang, label) y video.textTracks (R7, FR-019). */
  function collectSubtitles(el) {
    var subs = [];
    try {
      var tracks = el.getElementsByTagName('track');
      for (var i = 0; i < tracks.length; i++) {
        var t = tracks[i];
        var kind = String(t.getAttribute('kind') || 'subtitles').toLowerCase();
        if (kind !== 'subtitles' && kind !== 'captions') continue;
        var src = t.getAttribute('src') || t.src;
        if (!src) continue;
        subs.push({
          url: abs(src),
          language: t.getAttribute('srclang') || null,
          label: t.getAttribute('label') || null
        });
        if (subs.length >= 8) return subs;
      }
      if (el.textTracks) {
        for (var j = 0; j < el.textTracks.length; j++) {
          var tt = el.textTracks[j];
          if (!tt || (tt.kind !== 'subtitles' && tt.kind !== 'captions')) continue;
          var tsrc = tt.src || (tt.track && tt.track.src);
          if (!tsrc) continue;
          var exists = false;
          for (var q = 0; q < subs.length; q++) {
            if (subs[q].url === abs(tsrc)) { exists = true; break; }
          }
          if (!exists) {
            subs.push({ url: abs(tsrc), language: tt.language || null, label: tt.label || null });
          }
          if (subs.length >= 8) break;
        }
      }
    } catch (e) {}
    return subs;
  }

  function send(report) {
    try {
      if (!report || !isHttpMedia(report.url)) return;
      var bridge = window.DreamWebSniffer;
      if (!bridge || typeof bridge.onVideoFound !== 'function') return; // descarte silencioso
      bridge.onVideoFound(JSON.stringify(report));
      foundSomething = true;
    } catch (e) {}
  }

  function buildReport(el, url, mimeHint) {
    var r = {
      url: url,
      pageUrl: String(location.href),
      title: '',
      headers: {},
      subtitles: el ? collectSubtitles(el) : [],
      mediaKey: el ? mediaKey(el) : null
    };
    try {
      r.title = (el && el.getAttribute && el.getAttribute('title')) ||
        document.title || '';
      if (mimeHint) r.mimeType = mimeHint;
      else if (el && el.getAttribute) {
        var mt = el.getAttribute('type');
        if (mt) r.mimeType = mt;
      }
      if (el) {
        if (el.videoWidth > 0) r.width = el.videoWidth;
        if (el.videoHeight > 0) r.height = el.videoHeight;
        if (isFinite(el.duration) && el.duration >= 0) r.durationSec = el.duration;
        if (isFinite(el.currentTime) && el.currentTime >= 0) r.positionSec = el.currentTime;
      }
    } catch (e) {}
    return r;
  }

  /* URL propia del elemento (src/currentSrc): mutaciones vuelven a reportar. */
  function reportElementUrl(el, url) {
    try {
      if (!el || !url) return;
      var key = mediaKey(el);
      url = abs(url);
      if (lastElUrl[key] === url) return;
      lastElUrl[key] = url;
      send(buildReport(el, url));
    } catch (e) {}
  }

  /* URL de un <source> sibling: cada URL distinta es una rendición del video. */
  function reportSource(el, url, type) {
    try {
      if (!el || !url) return;
      var key = mediaKey(el);
      url = abs(url);
      var seen = seenSource[key] || (seenSource[key] = {});
      if (seen[url]) return;
      seen[url] = true;
      send(buildReport(el, url, type));
    } catch (e) {}
  }

  function scanElement(el) {
    try {
      if (!el || !el.tagName || el.tagName.toUpperCase() !== 'VIDEO') return;
      reportElementUrl(el, el.currentSrc || el.src || el.getAttribute('src'));
      var sources = el.getElementsByTagName('source');
      for (var i = 0; i < sources.length; i++) {
        var s = sources[i];
        reportSource(el, s.src || s.getAttribute('src'), s.getAttribute('type'));
      }
    } catch (e) {}
  }

  function scanAll() {
    try {
      var videos = document.getElementsByTagName('video');
      for (var i = 0; i < videos.length; i++) scanElement(videos[i]);
      // Iframes que apuntan directo a un stream (heredado del sniffer legado)
      var iframes = document.getElementsByTagName('iframe');
      for (var j = 0; j < iframes.length; j++) {
        var isrc = iframes[j].src;
        if (isrc && (isrc.indexOf('.m3u8') !== -1 || isrc.indexOf('.mp4') !== -1)) {
          send(buildReport(null, abs(isrc)));
        }
      }
    } catch (e) {}
  }

  function handleMutations(mutations) {
    try {
      for (var i = 0; i < mutations.length; i++) {
        var m = mutations[i];
        if (m.type === 'childList') {
          for (var n = 0; n < m.addedNodes.length; n++) {
            var node = m.addedNodes[n];
            if (!node || node.nodeType !== 1) continue;
            var tag = node.tagName ? node.tagName.toUpperCase() : '';
            if (tag === 'VIDEO') scanElement(node);
            else if (tag === 'SOURCE') {
              var v = node.parentNode;
              if (v && v.tagName && v.tagName.toUpperCase() === 'VIDEO') scanElement(v);
            } else if (node.querySelectorAll) {
              var inner = node.querySelectorAll('video');
              for (var k = 0; k < inner.length; k++) scanElement(inner[k]);
            }
          }
        } else if (m.type === 'attributes') {
          var t = m.target;
          var ttag = t && t.tagName ? t.tagName.toUpperCase() : '';
          if (ttag === 'VIDEO') scanElement(t);
          else if (ttag === 'SOURCE' && t.parentNode) scanElement(t.parentNode);
        }
      }
    } catch (e) {}
  }

  /* Sondeo acotado de players conocidos (jwplayer, videojs): setInterval
   * con apagado al primer hallazgo y backstop de 2 minutos (contrato §5). */
  function probePlayers() {
    try {
      if (window.jwplayer) {
        var p = window.jwplayer();
        if (p && p.getPlaylist) {
          var items = p.getPlaylist() || [];
          for (var i = 0; i < items.length; i++) {
            var f = items[i] && (items[i].file ||
              (items[i].sources && items[i].sources[0] && items[i].sources[0].file));
            if (f) send(buildReport(null, abs(f)));
          }
        }
      }
      if (window.videojs && window.videojs.players) {
        var pl = window.videojs.players;
        for (var id in pl) {
          try {
            var src = pl[id] && pl[id].currentSrc && pl[id].currentSrc();
            if (src) send(buildReport(null, abs(src)));
          } catch (e) {}
        }
      }
    } catch (e) {}
  }

  var pollId = setInterval(function () {
    try {
      if (foundSomething) { clearInterval(pollId); return; }
      probePlayers();
    } catch (e) {}
  }, 3000);
  setTimeout(function () { clearInterval(pollId); }, 120000);

  /* Hooks de HTMLMediaElement — SOLO modo document-start (R10). */
  if (!FALLBACK) {
    try {
      var proto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
      if (proto) {
        var desc = Object.getOwnPropertyDescriptor(proto, 'src');
        if (desc && desc.set && desc.get) {
          Object.defineProperty(proto, 'src', {
            configurable: true,
            enumerable: desc.enumerable,
            get: desc.get,
            set: function (v) {
              var ret = desc.set.call(this, v);
              var el = this;
              setTimeout(function () {
                try { reportElementUrl(el, el.currentSrc || v); } catch (e) {}
              }, 0);
              return ret;
            }
          });
        }
        var origSetAttr = proto.setAttribute;
        proto.setAttribute = function (name, value) {
          var ret = origSetAttr.call(this, name, value);
          try {
            var n = String(name).toLowerCase();
            if (n === 'src' || n.indexOf('data-') === 0) {
              var el = this;
              setTimeout(function () {
                try { reportElementUrl(el, el.currentSrc || el.src); } catch (e) {}
              }, 0);
            }
          } catch (e) {}
          return ret;
        };
        var origLoad = proto.load;
        proto.load = function () {
          var ret = origLoad.call(this);
          var el = this;
          setTimeout(function () {
            try { reportElementUrl(el, el.currentSrc || el.src); } catch (e) {}
          }, 0);
          return ret;
        };
      }
    } catch (e) {}
  }

  /* Observer sobre documentElement (en document-start body aún no existe). */
  try {
    var obsTarget = document.documentElement || document;
    var observer = new MutationObserver(handleMutations);
    observer.observe(obsTarget, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src', 'data-src']
    });
  } catch (e) {}

  /* Recaptura puntual de posición al lanzar (contrato §4 "Recaptura"). */
  window.__dwGetPosition = function (key) {
    try {
      var el = mediaElements[key];
      if (el && isFinite(el.currentTime) && el.currentTime >= 0) {
        return el.currentTime;
      }
    } catch (e) {}
    return null;
  };

  try { scanAll(); } catch (e) {}
})();
