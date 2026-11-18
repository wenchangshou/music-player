// Run the guest catalogue API on loopback. Only the relay exposes public routes.
const crypto = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");

const packageName = "@neteasecloudmusicapienhanced/api";
const api = require(packageName);
const { getXeapiPublicKey } = require(packageName + "/util/xeapiKey");
const { cookieToJson, generateDeviceId } = require(packageName + "/util");

let guestCookie;
const qrSecret = crypto.randomBytes(32);
const sessionPattern = /^[A-Za-z0-9._~%+-]{16,4096}$/;
const signature = payload => crypto.createHmac("sha256", qrSecret).update(payload).digest("base64url");

function qrKey(proof) {
    const [key, expires, mac, extra] = String(proof || "").split(".");
    if (extra !== undefined || !/^[a-zA-Z0-9_-]{8,128}$/.test(key) ||
        !/^\d{13}$/.test(expires) || Date.now() > Number(expires) ||
        !mac || mac.length !== 43) return null;
    const expected = signature(key + "." + expires);
    return crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(expected)) ? key : null;
}

async function qrStart() {
    const key = (await api.login_qr_key()).body?.data?.unikey;
    if (!key || !/^[a-zA-Z0-9_-]{8,128}$/.test(key)) return { code: 502 };
    const qr = (await api.login_qr_create({ key, qrimg: true })).body.data;
    const payload = key + "." + (Date.now() + 180000);
    return { code: 200, proof: payload + "." + signature(payload), qr: qr.qrimg };
}

async function qrPoll(proof) {
    const key = qrKey(proof);
    if (!key) return { code: 800 };
    const result = (await api.login_qr_check({ key })).body;
    if (result.code !== 803) return { code: [800, 801, 802].includes(result.code) ? result.code : 502 };
    const session = cookieToJson(result.cookie || "").MUSIC_U;
    return sessionPattern.test(session || "") ? { code: 803, session } : { code: 502 };
}

async function initializeGuest() {
    const key = await getXeapiPublicKey({}, generateDeviceId());
    fs.writeFileSync(path.join(os.tmpdir(), "xeapi_public_key"), JSON.stringify(key), { mode: 0o600 });
    const guest = await api.register_anonimous();
    const token = cookieToJson(guest.body.cookie || "").MUSIC_A;
    if (!token) throw new Error("NetEase did not issue a guest session");
    guestCookie = { MUSIC_A: token };
}

const routes = {
    "/cloudsearch": api.cloudsearch,
    "/album": api.album,
    "/artists": api.artists,
    "/artist/album": api.artist_album,
    "/playlist/track/all": api.playlist_track_all,
    "/song/url/v1": api.song_url_v1,
    "/lyric": api.lyric,
    "/comment/music": api.comment_music,
    "/song/red/count": api.song_red_count,
    "/recommend/songs": api.recommend_songs,
    "/recommend/resource": api.recommend_resource,
    "/personalized": api.personalized,
    "/top/playlist": api.top_playlist,
    "/user/account": api.user_account,
    "/user/playlist": api.user_playlist,
};
const signedInRoutes = new Set(["/song/url/v1", "/recommend/songs", "/recommend/resource", "/user/account", "/user/playlist", "/playlist/track/all"]);
const params = ["id", "keywords", "type", "offset", "limit", "level", "before", "uid", "cat"];

function reply(response, status, body) {
    response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    response.end(JSON.stringify(body));
}

const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, "http://127.0.0.1");
    const start = url.pathname === "/login/qr/start";
    const poll = url.pathname === "/login/qr/poll";
    if (request.method !== "GET" || (!start && !poll && !Object.hasOwn(routes, url.pathname))) {
        reply(response, 404, { code: 404 });
        return;
    }

    const query = Object.fromEntries(params.filter(key => url.searchParams.has(key))
        .map(key => [key, url.searchParams.get(key)]));
    try {
        if (start || poll) {
            reply(response, 200, start ? await qrStart() : await qrPoll(request.headers["x-netease-qr-proof"]));
            return;
        }
        const session = request.headers["x-netease-session"];
        const cookie = signedInRoutes.has(url.pathname) && sessionPattern.test(session || "")
            ? { MUSIC_U: session } : guestCookie;
        const result = await routes[url.pathname]({ ...query, cookie });
        reply(response, 200, result.body);
    } catch (error) {
        reply(response, 200, { code: error?.body?.code || 502 });
    }
});

initializeGuest().catch(() => {
    console.error("NetEase guest session initialization failed");
}).finally(() => {
    server.listen(3000, "127.0.0.1");
});
