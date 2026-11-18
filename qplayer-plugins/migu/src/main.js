"use strict";

// 咪咕音乐源插件。接口与字段映射参考 go-music-dl / music-lib 的 migu 实现。

var UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1";
var REFERER = "http://music.migu.cn/";
var MAGIC_USER = "15548614588710179085069";
var PAGE = 50;
var MAX_SONGS = 3000;

function call(method, args) { return qplayer.call(method, args || {}); }

function secureUrl(value) {
  value = String(value || "").trim();
  if (!value) return "";
  if (value.indexOf("//") === 0) return "https:" + value;
  if (value.indexOf("http://") === 0) return "https://" + value.slice(7);
  if (value.indexOf("https://") === 0) return value;
  if (value.indexOf("/") === 0) return "https://d.musicapp.migu.cn" + value;
  return value;
}

function str(value) {
  if (value == null) return "";
  if (typeof value === "number") return String(Math.floor(value));
  return String(value).trim();
}

function num(value) {
  if (typeof value === "number") return Math.floor(value);
  var n = parseInt(String(value || "").trim(), 10);
  return isNaN(n) ? 0 : n;
}

function first() {
  for (var i = 0; i < arguments.length; i++) {
    var value = str(arguments[i]);
    if (value) return value;
  }
  return "";
}

function cookieHeader(cookies) {
  var parts = [];
  Object.keys(cookies || {}).forEach(function (name) {
    if (cookies[name]) parts.push(name + "=" + cookies[name]);
  });
  return parts.join("; ");
}

function loadCookies() {
  return call("credentials.get", { key: "cookies" }).then(function (stored) {
    if (!stored) return {};
    try { return JSON.parse(stored); } catch (_) { return {}; }
  }, function () { return {}; });
}

function headers(extra) {
  return loadCookies().then(function (cookies) {
    var all = { "User-Agent": UA, "Referer": REFERER };
    var cookie = cookieHeader(cookies);
    if (cookie) all.Cookie = cookie;
    if (extra) Object.keys(extra).forEach(function (key) { all[key] = extra[key]; });
    return all;
  });
}

function httpGet(url, extra) {
  return headers(extra).then(function (all) {
    return call("http.request", { url: url, method: "GET", headers: all, timeoutMs: 15000 });
  }).then(function (response) {
    if (response.status < 200 || response.status >= 300) throw new Error("HTTP " + response.status);
    return { body: String(response.body || ""), finalUrl: response.finalUrl || url, status: response.status };
  });
}

function httpGetJson(url) {
  return httpGet(url).then(function (res) { return JSON.parse(res.body || "{}"); });
}

function pickImage(items) {
  items = items || [];
  var preferred = ["02", "01", "03"];
  for (var p = 0; p < preferred.length; p++) {
    for (var i = 0; i < items.length; i++) {
      if (items[i].imgSizeType === preferred[p] && items[i].img) return items[i].img;
    }
  }
  for (var j = 0; j < items.length; j++) if (items[j].img) return items[j].img;
  return "";
}

function artistNames(item) {
  var names = [];
  var seen = {};
  function add(name) {
    name = str(name);
    if (!name || seen[name]) return;
    seen[name] = true;
    names.push(name);
  }
  (item.singers || []).forEach(function (a) { add(a.name); });
  (item.singerList || []).forEach(function (a) { add(a.name); });
  (item.artists || []).forEach(function (a) { add(a.name); });
  if (!names.length) String(item.singer || "").split("|").forEach(add);
  return names;
}

function firstNonZero() {
  for (var i = 0; i < arguments.length; i++) {
    var value = str(arguments[i]);
    if (value && value !== "0") return value;
  }
  return "";
}

function formatExt(formatType, formatCode) {
  formatType = String(formatType || "").toUpperCase();
  formatCode = String(formatCode || "");
  if (formatType.indexOf("SQ") >= 0 || formatCode.indexOf("011") === 0) return "flac";
  return "mp3";
}

function convertItem(item, allowPaid) {
  item = item || {};
  var rates = item.rateFormats && item.rateFormats.length ? item.rateFormats : (item.audioFormats || []);
  if (!rates.length) return null;
  var duration = num(item.duration);
  var pqSize = 0;
  var candidates = [];
  rates.forEach(function (fmtItem, index) {
    var sizeVal = num(firstNonZero(fmtItem.androidSize, fmtItem.asize, fmtItem.size, fmtItem.isize));
    var ext = first(fmtItem.androidFileType, fmtItem.fileType) || formatExt(fmtItem.formatType, first(fmtItem.aformat, fmtItem.iformat));
    if (fmtItem.formatType === "PQ") pqSize = sizeVal;
    if (!duration && sizeVal > 0) {
      var bitrate = fmtItem.formatType === "PQ" ? 128000 : (fmtItem.formatType === "HQ" ? 320000 : (fmtItem.formatType === "LQ" ? 64000 : 0));
      if (bitrate) duration = Math.floor((sizeVal * 8) / bitrate);
    }
    var priceVal = num(fmtItem.price);
    var tags = fmtItem.showTag && fmtItem.showTag.length ? fmtItem.showTag : (fmtItem.showTags || []);
    var vip = false;
    tags.forEach(function (tag) { if (tag === "vip") vip = true; });
    var hiddenPaid = item.chargeAuditions === "1" && priceVal >= 200;
    if (allowPaid || (!vip && !hiddenPaid)) candidates.push({ index: index, size: sizeVal, ext: ext });
  });
  if (!candidates.length) return null;
  candidates.sort(function (a, b) { return b.size - a.size; });
  var best = rates[candidates[0].index];
  var names = artistNames(item);
  var albumName = str(item.album);
  if (item.albums && item.albums[0] && item.albums[0].name) albumName = item.albums[0].name;
  var albumId = first(item.albumId, item.albums && item.albums[0] && item.albums[0].id);
  var cover = secureUrl(first(pickImage(item.imgItems), pickImage(item.albumImgs), item.img1, item.img2, item.img3));
  var contentId = first(item.contentId, item.copyrightId);
  var title = first(item.name, item.songName);
  if (!contentId || !title) return null;
  return {
    id: contentId + "|" + best.resourceType + "|" + best.formatType,
    title: title,
    durationMs: duration > 10000 ? duration : duration * 1000,
    artworkUrl: cover,
    artworkThumbUrl: cover,
    artists: names.map(function (name, i) {
      var src = (item.singers || item.artists || [])[i];
      var id = src && src.id ? String(src.id) : name;
      return { id: id, name: name };
    }),
    album: albumName ? { id: albumId || albumName, name: albumName } : undefined,
    playable: true,
    trial: false,
    restricted: false
  };
}

function contentIdOf(id) {
  id = str(id);
  return id.indexOf("|") >= 0 ? id.split("|")[0] : id;
}

function pageArgs(args) {
  var limit = Math.max(1, Math.min(Number(args && args.limit || 20), 100));
  var cursor = String(args && args.cursor || "").trim();
  var page = cursor ? Math.max(1, num(cursor)) : 1;
  return { query: String(args && args.query || "").trim(), limit: limit, page: page };
}

function searchUrl(keyword, page, limit, sw) {
  return "http://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do?ua=Android_migu&version=5.0.1"
    + "&text=" + encodeURIComponent(keyword) + "&pageNo=" + page + "&pageSize=" + limit
    + "&searchSwitch=" + encodeURIComponent(sw);
}

function searchSongs(args) {
  var p = pageArgs(args);
  if (!p.query) return { items: [], nextCursor: "" };
  return httpGetJson(searchUrl(p.query, p.page, p.limit,
    '{"song":1,"album":0,"singer":0,"tagSong":0,"mvSong":0,"songlist":0,"bestShow":1}')).then(function (body) {
    var items = [];
    ((((body.songResultData || {}).result) || [])).forEach(function (item) {
      var song = convertItem(item, false) || convertItem(item, true);
      if (song) items.push(song);
    });
    return { items: items, nextCursor: items.length >= p.limit ? String(p.page + 1) : "" };
  });
}

function searchAlbums(args) {
  var p = pageArgs(args);
  if (!p.query) return { items: [], nextCursor: "" };
  return httpGetJson(searchUrl(p.query, p.page, p.limit,
    '{"song":0,"album":1,"singer":0,"tagSong":0,"mvSong":0,"songlist":0,"bestShow":1}')).then(function (body) {
    var items = [];
    ((((body.albumResultData || {}).result) || [])).forEach(function (item) {
      var id = str(item.id);
      var name = str(item.name);
      if (!id || !name) return;
      var cover = secureUrl(pickImage(item.imgItems));
      items.push({
        id: id, name: name, description: first(item.desc, item.publishDate),
        artworkUrl: cover, artworkThumbUrl: cover,
        artists: artistNames({ singer: item.singer }).map(function (n) { return { id: n, name: n }; })
      });
    });
    return { items: items, nextCursor: items.length >= p.limit ? String(p.page + 1) : "" };
  });
}

function resourceInfo(resourceType, resourceId) {
  return httpGetJson("http://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceType="
    + encodeURIComponent(resourceType) + "&resourceId=" + encodeURIComponent(resourceId)
    + "&needSimple=00");
}

function songDetails(args) {
  var ids = (args && args.ids || []).map(str).filter(Boolean).slice(0, 50);
  if (!ids.length) return Promise.resolve([]);
  var out = [];
  return ids.reduce(function (chain, id) {
    return chain.then(function () {
      return resourceInfo("2", contentIdOf(id)).then(function (body) {
        var item = (body.resource || [])[0];
        var song = item && (convertItem(item, true) || convertItem(item, false));
        if (song) out.push(song);
      }, function () {});
    });
  }, Promise.resolve()).then(function () { return out; });
}

function collectPaged(fetchPage) {
  var songs = [];
  var seen = {};
  var total = 0;
  function next(page) {
    if (page > 60 || songs.length >= MAX_SONGS) return songs;
    return fetchPage(page).then(function (data) {
      if (!total) total = num(data.totalCount);
      var list = data.songList || [];
      if (!list.length) return songs;
      list.forEach(function (item) {
        var song = convertItem(item, true) || convertItem(item, false);
        if (!song) return;
        var key = contentIdOf(song.id);
        if (seen[key]) return;
        seen[key] = true;
        songs.push(song);
      });
      if (list.length < PAGE || (total > 0 && songs.length >= total)) return songs;
      return next(page + 1);
    });
  }
  return next(1);
}

function playlistDetails(args) {
  var id = str(args && args.id);
  if (!id) throw new Error("缺少歌单 ID");
  return Promise.all([
    collectPaged(function (page) {
      return httpGetJson("https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0?pageNo="
        + page + "&pageSize=" + PAGE + "&playlistId=" + encodeURIComponent(id)).then(function (body) {
        if (body.code && body.code !== "000000") throw new Error(body.info || ("咪咕错误 " + body.code));
        return body.data || {};
      });
    }),
    httpGetJson("https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?needSimple=00&resourceType=2021&resourceId="
      + encodeURIComponent(id)).then(function (body) { return (body.resource || [])[0] || {}; }, function () { return {}; })
  ]).then(function (both) {
    var songs = both[0];
    var info = both[1];
    var cover = secureUrl(first(info.originalImgUrl, (info.imgItem || {}).img, (info.imgItem || {}).webpImg));
    if (!cover && songs[0]) cover = songs[0].artworkUrl;
    return {
      id: first(info.musicListId, id),
      name: first(info.title, id),
      description: str(info.summary),
      artworkUrl: cover,
      artworkThumbUrl: cover,
      trackCount: num(info.musicNum) || songs.length,
      playCount: num((info.opNumItem || {}).playNum),
      owner: { id: "", name: str(info.ownerName) },
      songs: songs
    };
  });
}

function albumDetails(args) {
  var id = str(args && args.id);
  if (!id) throw new Error("缺少专辑 ID");
  return Promise.all([
    collectPaged(function (page) {
      return httpGetJson("https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/queryAlbumSong?albumId="
        + encodeURIComponent(id) + "&pageNo=" + page + "&pageSize=" + PAGE).then(function (body) {
        if (body.code && body.code !== "000000") throw new Error(body.info || ("咪咕错误 " + body.code));
        return body.data || {};
      });
    }),
    httpGetJson("https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?needSimple=00&resourceType=2003&resourceId="
      + encodeURIComponent(id)).then(function (body) { return (body.resource || [])[0] || {}; }, function () { return {}; })
  ]).then(function (both) {
    var songs = both[0];
    var info = both[1];
    var cover = secureUrl(pickImage(info.imgItems));
    return {
      id: first(info.albumId, id),
      name: first(info.title, id),
      description: str(info.summary),
      artworkUrl: cover,
      artworkThumbUrl: cover,
      trackCount: num(info.totalCount) || songs.length,
      artists: artistNames({ singer: info.singer }).map(function (n) { return { id: n, name: n }; }),
      songs: songs
    };
  });
}

function artistDetails(args) {
  var id = str(args && args.id);
  if (!id) throw new Error("缺少歌手 ID");
  return searchSongs({ query: id, limit: 50, cursor: "" }).then(function (page) {
    var songs = (page.items || []).filter(function (song) {
      return (song.artists || []).some(function (artist) {
        return artist.id === id || artist.name === id;
      });
    });
    if (!songs.length) songs = page.items || [];
    return { id: id, name: id, songs: songs.slice(0, 50), songCount: songs.length };
  });
}

function home(args) {
  var limit = Math.max(1, Math.min(Number(args && args.limit || 20), 100));
  if (args && args.operation === "recommendSongs") return [];
  return httpGetJson(searchUrl("华语", 1, limit,
    '{"song":0,"album":0,"singer":0,"tagSong":0,"mvSong":0,"songlist":1,"bestShow":1}')).then(function (body) {
    var playlists = [];
    ((((body.songListResultData || {}).result) || [])).forEach(function (item) {
      var id = str(item.id);
      var name = str(item.name);
      if (!id || !name) return;
      var cover = secureUrl(first(item.musicListPicUrl, pickImage(item.imgItems)));
      playlists.push({
        id: id, name: name, artworkUrl: cover, artworkThumbUrl: cover,
        trackCount: num(item.musicNum), playCount: num(item.playNum),
        owner: { id: "", name: first(item.userName, item.ownerName) }
      });
    });
    return { songs: [], playlists: playlists.slice(0, limit), sections: [] };
  });
}

function parseSongId(id) {
  var parts = str(id).split("|");
  return {
    contentId: parts[0] || "",
    resourceType: parts[1] || "2",
    formatType: parts[2] || "PQ"
  };
}

function resolveStream(args) {
  var parsed = parseSongId(args && args.id);
  if (!parsed.contentId) throw new Error("缺少歌曲 ID");
  function listenUrl(formatType) {
    return "http://app.pd.nf.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do?toneFlag="
      + encodeURIComponent(formatType) + "&netType=00&userId=" + MAGIC_USER
      + "&ua=Android_migu&version=5.1&copyrightId=0&contentId=" + encodeURIComponent(parsed.contentId)
      + "&resourceType=" + encodeURIComponent(parsed.resourceType) + "&channel=0";
  }
  var formats = [parsed.formatType, "PQ", "HQ", "LQ"];
  function tryFormat(index) {
    if (index >= formats.length) throw new Error("暂无可用播放地址");
    var url = listenUrl(formats[index]);
    return headers({ Range: "bytes=0-1" }).then(function (all) {
      return call("http.request", { url: url, method: "HEAD", headers: all, timeoutMs: 15000 });
    }).then(function (response) {
      var finalUrl = response.finalUrl || url;
      if (finalUrl && finalUrl.indexOf("listenSong.do") < 0) {
        return {
          url: secureUrl(finalUrl),
          headers: { "User-Agent": UA, "Referer": REFERER },
          mimeType: "audio/mpeg",
          expiresAtMs: Date.now() + 15 * 60 * 1000,
          trial: false,
          cacheable: false
        };
      }
      return headers({ Range: "bytes=0-1" }).then(function (all) {
        return call("http.request", { url: url, method: "GET", headers: all, timeoutMs: 15000 });
      }).then(function (getRes) {
        var play = getRes.finalUrl || url;
        if (!play) return tryFormat(index + 1);
        return {
          url: secureUrl(play),
          headers: { "User-Agent": UA, "Referer": REFERER },
          mimeType: "audio/mpeg",
          expiresAtMs: Date.now() + 15 * 60 * 1000,
          trial: false,
          cacheable: false
        };
      });
    }, function () { return tryFormat(index + 1); });
  }
  return tryFormat(0);
}

function lyrics(args) {
  var id = contentIdOf(args && args.id);
  if (!id) return { assets: [] };
  return resourceInfo("2", id).then(function (body) {
    var item = (body.resource || [])[0] || {};
    var lyricUrl = first(item.lrcUrl, item.lyricUrl);
    if (!lyricUrl) return { assets: [] };
    lyricUrl = secureUrl(lyricUrl);
    return httpGet(lyricUrl, { "User-Agent": UA, "Referer": "https://y.migu.cn/" }).then(function (res) {
      var textLrc = String(res.body || "").trim();
      return textLrc ? { assets: [{ format: "lrc", role: "original", text: textLrc }] } : { assets: [] };
    });
  }, function () { return { assets: [] }; });
}

function account() {
  return loadCookies().then(function (cookies) {
    var uid = first(cookies.userId, cookies.userid, cookies.uid);
    if (!uid) return { loggedIn: false };
    return {
      loggedIn: true, id: uid,
      displayName: first(cookies.nickname, cookies.userName, "咪咕用户 " + uid),
      avatarUrl: secureUrl(cookies.photoUrl || cookies.avatar),
      membershipTier: 0, level: 0, signature: ""
    };
  });
}

function parseCookieString(raw) {
  var parsed = {};
  String(raw || "").split(";").forEach(function (part) {
    var at = part.indexOf("=");
    if (at <= 0) return;
    parsed[part.slice(0, at).trim()] = part.slice(at + 1).trim();
  });
  return parsed;
}

function login(args) {
  switch (args && args.operation) {
    case "methods":
      return [{
        id: "cookie", type: "credential", label: "Cookie",
        instructions: "在咪咕音乐网页或客户端登录后，粘贴请求头中的 Cookie。未登录也可搜索和播放部分曲目。",
        credentialLabel: "咪咕 Cookie"
      }];
    case "submit": {
      var parsed = parseCookieString(args.credential);
      if (!Object.keys(parsed).length) {
        return { methodId: "cookie", status: "failed", message: "Cookie 为空" };
      }
      return call("credentials.put", { key: "cookies", value: JSON.stringify(parsed) })
        .then(function () { return account(); })
        .then(function (profile) {
          return { methodId: "cookie", status: "success", account: profile };
        });
    }
    case "logout":
      return call("credentials.delete", { key: "cookies" }).then(function () { return true; }, function () { return true; });
    default:
      throw new Error("该登录方式不需要此操作");
  }
}

module.exports = {
  handlers: {
    searchSongs: searchSongs,
    searchAlbums: searchAlbums,
    songDetails: songDetails,
    playlistDetails: playlistDetails,
    albumDetails: albumDetails,
    artistDetails: artistDetails,
    home: home,
    resolveStream: resolveStream,
    lyrics: lyrics,
    account: account,
    login: login
  }
};
