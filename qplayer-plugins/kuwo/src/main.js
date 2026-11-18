"use strict";

// 酷我音乐源插件。接口与字段映射参考 go-music-dl / music-lib 的 kuwo 实现。

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
var PLAYLIST_PAGE = 100;
var MAX_PLAYLIST_SONGS = 3000;

function call(method, args) { return qplayer.call(method, args || {}); }

function secureUrl(value) {
  value = String(value || "").trim();
  if (!value) return "";
  if (value.indexOf("//") === 0) value = "https:" + value;
  else if (value.indexOf("http://") === 0) value = "https://" + value.slice(7);
  else if (value.indexOf("https://") !== 0) {
    if (value.indexOf("img") === 0) value = "https://" + value;
    else value = "https://img1.kuwo.cn/star/albumcover/" + value.replace(/^\//, "");
  }
  return value.replace("/120/", "/500/").replace("/150/", "/500/")
    .replace("/240/", "/500/").replace("_100.", "_500.")
    .replace("_120.", "_500.").replace("_150.", "_500.").replace("_240.", "_500.");
}

function thumbUrl(value) {
  var url = secureUrl(value);
  return url ? url.replace("/500/", "/150/").replace("_500.", "_150.") : "";
}

function text(value) {
  value = String(value == null ? "" : value);
  return value.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, "\"").replace(/&#39;/g, "'").replace(/\u00a0/g, " ").trim();
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

function headers() {
  return loadCookies().then(function (cookies) {
    var all = { "User-Agent": UA, "Referer": "https://www.kuwo.cn/" };
    var cookie = cookieHeader(cookies);
    if (cookie) all.Cookie = cookie;
    return all;
  });
}

function httpGet(url) {
  return headers().then(function (all) {
    return call("http.request", {
      url: url, method: "GET", headers: all, timeoutMs: 15000
    });
  }).then(function (response) {
    if (response.status < 200 || response.status >= 300) throw new Error("HTTP " + response.status);
    return String(response.body || "");
  });
}

function httpGetJson(url) {
  return httpGet(url).then(function (body) {
    return JSON.parse(body || "{}");
  });
}

function httpGetLegacyJson(url) {
  return httpGet(url).then(function (body) {
    return JSON.parse(String(body || "").replace(/'/g, "\""));
  });
}

function durationMsOf() {
  for (var i = 0; i < arguments.length; i++) {
    var value = arguments[i];
    if (value == null || value === "") continue;
    if (typeof value === "string" && value.indexOf(":") >= 0) {
      var parts = value.split(":");
      var m = num(parts[0]);
      var s = num(parts[1]);
      if (m || s) return (m * 60 + s) * 1000;
      continue;
    }
    var n = num(value);
    if (n <= 0) continue;
    return n > 10000 ? n : n * 1000;
  }
  return 0;
}

function artistsOf(name) {
  name = text(name);
  if (!name) return [];
  return name.split(/[&,\/、]/).map(function (part) {
    part = part.trim();
    return part ? { id: part, name: part } : null;
  }).filter(Boolean);
}

function songDto(raw) {
  raw = raw || {};
  var id = first(raw.id, raw.rid, String(raw.MUSICRID || "").replace(/^MUSIC_/, ""));
  var title = text(first(raw.title, raw.name, raw.song_name, raw.SONGNAME, raw.songName));
  if (!id || !title) return null;
  var artist = text(first(raw.artist, raw.artist_name, raw.ARTIST, raw.aartist));
  var albumName = text(first(raw.album, raw.ALBUM));
  var albumId = first(raw.albumId, raw.albumid, raw.album_id);
  var cover = secureUrl(first(raw.artworkUrl, raw.cover, raw.albumpic, raw.pic, raw.hts_MVPIC, raw.pic120, raw.hts_img, raw.img));
  var durationMs = durationMsOf(raw.durationMs, raw.duration, raw.DURATION, raw.songTimeMinutes);
  return {
    id: id,
    title: title,
    durationMs: durationMs,
    artworkUrl: cover,
    artworkThumbUrl: thumbUrl(cover),
    artists: artistsOf(artist),
    album: albumName ? { id: albumId || albumName, name: albumName } : undefined,
    playable: true,
    trial: false,
    restricted: false
  };
}

function songsOf(list) {
  var out = [];
  (list || []).forEach(function (item) {
    var song = songDto(item);
    if (song) out.push(song);
  });
  return out;
}

function playlistDto(raw, songs) {
  raw = raw || {};
  var id = first(raw.id, raw.playlistid, raw.pid);
  var name = text(first(raw.name, raw.title));
  if (!id || !name) return null;
  var cover = secureUrl(first(raw.pic, raw.img, raw.cover));
  if (cover) cover = cover.replace("_150.", "_700.");
  return {
    id: id,
    name: name,
    description: text(first(raw.info, raw.desc, raw.intro)),
    artworkUrl: cover,
    artworkThumbUrl: thumbUrl(cover),
    trackCount: num(first(raw.trackCount, raw.total, raw.validtotal, raw.songnum, raw.songNum, raw.count, raw.musicnum)),
    playCount: num(first(raw.playnum, raw.listencnt, raw.PLAYCNT)),
    owner: { id: "", name: text(first(raw.uname, raw.nickname, raw.creator)) },
    songs: songs || []
  };
}

function albumDto(raw, songs) {
  raw = raw || {};
  var id = first(raw.id, raw.albumid);
  var name = text(raw.name);
  if (!id || !name) return null;
  var cover = secureUrl(first(raw.hts_img, raw.img, raw.pic));
  return {
    id: id,
    name: name,
    description: text(raw.info),
    artworkUrl: cover,
    artworkThumbUrl: thumbUrl(cover),
    trackCount: num(first(raw.songnum, raw.musiccnt, raw.trackCount)),
    artists: artistsOf(first(raw.aartist, raw.artist)),
    songs: songs || []
  };
}

function pageArgs(args) {
  var limit = Math.max(1, Math.min(Number(args && args.limit || 20), 100));
  var cursor = String(args && args.cursor || "").trim();
  var page = cursor ? Math.max(0, num(cursor)) : 0;
  return { query: String(args && args.query || "").trim(), limit: limit, page: page };
}

function searchSongs(args) {
  var p = pageArgs(args);
  if (!p.query) return { items: [], nextCursor: "" };
  var url = "http://www.kuwo.cn/search/searchMusicBykeyWord?vipver=1&client=kt&ft=music&cluster=0"
    + "&strategy=2012&encoding=utf8&rformat=json&mobi=1&issubtitle=1&show_copyright_off=1"
    + "&pn=" + p.page + "&rn=" + p.limit + "&all=" + encodeURIComponent(p.query);
  return httpGetJson(url).then(function (body) {
    var list = (body.abslist || []).filter(function (item) { return num(item.bitSwitch) !== 0; });
    var items = songsOf(list);
    return { items: items, nextCursor: items.length >= p.limit ? String(p.page + 1) : "" };
  });
}

function searchCollection(keyword, ft, page, limit) {
  return httpGetLegacyJson("http://search.kuwo.cn/r.s?all=" + encodeURIComponent(keyword)
    + "&ft=" + encodeURIComponent(ft) + "&itemset=web_2013&client=kt&pcmp4=1&geo=c&vipver=1"
    + "&pn=" + page + "&rn=" + limit + "&rformat=json&encoding=utf8");
}

function searchAlbums(args) {
  var p = pageArgs(args);
  if (!p.query) return { items: [], nextCursor: "" };
  return searchCollection(p.query, "album", p.page, p.limit).then(function (body) {
    var items = [];
    (body.albumlist || []).forEach(function (item) {
      var album = albumDto(item);
      if (album) items.push(album);
    });
    return { items: items, nextCursor: items.length >= p.limit ? String(p.page + 1) : "" };
  });
}

function songInfo(id) {
  return httpGetJson("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId="
    + encodeURIComponent(id) + "&httpsStatus=1").then(function (body) {
    var info = ((body.data || {}).songinfo) || {};
    return songDto({
      id: id,
      title: info.songName,
      artist: info.artist,
      album: info.album,
      albumid: info.albumId,
      duration: info.duration,
      songTimeMinutes: info.songTimeMinutes,
      pic: info.pic
    }) || { id: id, title: "Kuwo_Song_" + id, artists: [], playable: true };
  }, function () {
    return { id: id, title: "Kuwo_Song_" + id, artists: [], playable: true };
  });
}

function songDetails(args) {
  var ids = (args && args.ids || []).map(str).filter(Boolean).slice(0, 50);
  if (!ids.length) return Promise.resolve([]);
  var out = [];
  return ids.reduce(function (chain, id) {
    return chain.then(function () {
      return songInfo(id).then(function (song) { out.push(song); });
    });
  }, Promise.resolve()).then(function () { return out; });
}

function playlistPage(id, page) {
  return httpGetJson("http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=" + encodeURIComponent(id)
    + "&pn=" + page + "&rn=" + PLAYLIST_PAGE + "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=1&newver=1");
}

function playlistDetails(args) {
  var id = str(args && args.id);
  if (!id) throw new Error("缺少歌单 ID");
  var songs = [];
  var seen = {};
  var meta = null;
  var total = 0;
  function next(page) {
    if (page > 30 || songs.length >= MAX_PLAYLIST_SONGS) return playlistDto(meta, songs);
    return playlistPage(id, page).then(function (body) {
      if (!meta) {
        meta = body;
        meta.id = id;
        total = num(first(body.total, body.validtotal));
      }
      var list = body.musiclist || [];
      list.forEach(function (item) {
        var song = songDto(item);
        if (!song || seen[song.id]) return;
        seen[song.id] = true;
        songs.push(song);
      });
      if (!list.length || list.length < PLAYLIST_PAGE || (total > 0 && songs.length >= total)) {
        return playlistDto(meta, songs);
      }
      return next(page + 1);
    });
  }
  return next(0).then(function (playlist) {
    if (!playlist) throw new Error("歌单不存在或为空");
    return playlist;
  });
}

function albumDetails(args) {
  var id = str(args && args.id);
  if (!id) throw new Error("缺少专辑 ID");
  var songs = [];
  var seen = {};
  var meta = null;
  var total = 0;
  function next(page) {
    var url = "http://search.kuwo.cn/r.s?pn=" + page + "&rn=100&stype=albuminfo&albumid="
      + encodeURIComponent(id) + "&sortby=0&alflac=1&show_copyright_off=1&pcmp4=1&encoding=utf8";
    return httpGetLegacyJson(url).then(function (body) {
      if (!meta) {
        meta = body;
        meta.id = first(body.albumid, body.id, id);
        total = num(body.songnum);
      }
      var list = body.musiclist || [];
      list.forEach(function (item) {
        var song = songDto(item);
        if (!song || seen[song.id]) return;
        seen[song.id] = true;
        if (!song.album && meta.name) song.album = { id: meta.id, name: text(meta.name) };
        songs.push(song);
      });
      if (!list.length || list.length < 100 || (total > 0 && songs.length >= total)) {
        var album = albumDto(meta, songs);
        if (!album) throw new Error("专辑不存在或为空");
        if (!album.trackCount) album.trackCount = songs.length;
        return album;
      }
      return next(page + 1);
    });
  }
  return next(0);
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
  var limit = Math.max(1, Math.min(Number(args && args.limit || 30), 100));
  if (args && args.operation === "recommendSongs") return [];
  return httpGetJson("http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?pn=0&rn="
    + limit + "&order=hot").then(function (body) {
    var list = (((body.data || {}).data) || []).map(function (item) {
      return playlistDto({
        id: item.id, name: item.name, img: item.img, listencnt: item.listencnt,
        songnum: item.songnum, total: item.total, count: item.count, musicnum: item.musicnum,
        uname: item.uname, desc: item.desc
      });
    }).filter(Boolean);
    return { songs: [], playlists: list.slice(0, limit), sections: [] };
  });
}

function qualitiesFor(quality) {
  quality = String(quality || "standard").toLowerCase();
  if (quality === "lossless" || quality === "hires" || quality === "exhigh") {
    return ["2000kflac", "flac", "320kmp3", "128kmp3"];
  }
  if (quality === "higher" || quality === "high") return ["320kmp3", "128kmp3"];
  return ["320kmp3", "128kmp3", "flac"];
}

function mimeOf(url) {
  url = String(url || "").toLowerCase();
  if (url.indexOf(".flac") >= 0) return "audio/flac";
  if (url.indexOf(".aac") >= 0) return "audio/aac";
  return "audio/mpeg";
}

function tryBr(rid, list, index) {
  if (index >= list.length) return Promise.resolve("");
  var user = "C_APK_guanwang_" + Date.now() + Math.floor(Math.random() * 1000000);
  var url = "https://mobi.kuwo.cn/mobi.s?f=web&source=kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk"
    + "&from=PC&type=convert_url_with_sign&br=" + encodeURIComponent(list[index])
    + "&rid=" + encodeURIComponent(rid) + "&user=" + encodeURIComponent(user);
  return httpGetJson(url).then(function (body) {
    var play = ((body.data || {}).url) || "";
    return play || tryBr(rid, list, index + 1);
  }, function () { return tryBr(rid, list, index + 1); });
}

function fallbackPlayUrl(rid) {
  return loadCookies().then(function (cookies) {
    var all = {
      "User-Agent": UA,
      "Referer": "https://www.kuwo.cn/",
      "Secret": "kuwo_web_secret",
      "Cookie": cookieHeader(cookies) + (cookies.kw_token ? "" : "; kw_token=secret_token")
    };
    return call("http.request", {
      url: "https://www.kuwo.cn/api/v1/www/music/playUrl?mid=" + encodeURIComponent(rid)
        + "&type=music&httpsStatus=1",
      method: "GET", headers: all, timeoutMs: 15000
    });
  }).then(function (response) {
    var body = {};
    try { body = JSON.parse(response.body || "{}"); } catch (_) {}
    return ((body.data || {}).url) || "";
  }, function () { return ""; });
}

function resolveStream(args) {
  var id = str(args && args.id);
  if (!id) throw new Error("缺少歌曲 ID");
  return tryBr(id, qualitiesFor(args && args.quality), 0).then(function (url) {
    return url || fallbackPlayUrl(id);
  }).then(function (url) {
    if (!url) throw new Error("暂无可用播放地址（可能受版权限制）");
    return {
      url: secureUrl(url),
      headers: { "User-Agent": UA, "Referer": "https://www.kuwo.cn/" },
      mimeType: mimeOf(url),
      expiresAtMs: Date.now() + 15 * 60 * 1000,
      trial: false,
      cacheable: false
    };
  });
}

function lyricsFromList(list) {
  var lines = [];
  (list || []).forEach(function (line) {
    var secs = parseFloat(line.time || "0");
    if (isNaN(secs)) secs = 0;
    var m = Math.floor(secs / 60);
    var s = Math.floor(secs % 60);
    var cs = Math.floor((secs - Math.floor(secs)) * 100);
    var mm = m < 10 ? "0" + m : String(m);
    var ss = s < 10 ? "0" + s : String(s);
    var cc = cs < 10 ? "0" + cs : String(cs);
    lines.push("[" + mm + ":" + ss + "." + cc + "]" + String(line.lineLyric || ""));
  });
  return lines.join("\n");
}

function lyrics(args) {
  var id = str(args && args.id);
  if (!id) return { assets: [] };
  return httpGetJson("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId="
    + encodeURIComponent(id) + "&httpsStatus=1").then(function (body) {
    var textLrc = lyricsFromList(((body.data || {}).lrclist) || []);
    return textLrc ? { assets: [{ format: "lrc", role: "original", text: textLrc }] } : { assets: [] };
  }, function () { return { assets: [] }; });
}

function account() {
  return loadCookies().then(function (cookies) {
    var uid = first(cookies.userid, cookies.uid, cookies.loginUid);
    if (!uid) return { loggedIn: false };
    return {
      loggedIn: true, id: uid,
      displayName: first(cookies.uname, cookies.username, "酷我用户 " + uid),
      avatarUrl: secureUrl(cookies.pic),
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
        instructions: "在酷我网页或客户端登录后，粘贴请求头中的 Cookie。未登录也可搜索和播放部分曲目。",
        credentialLabel: "酷我 Cookie"
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
