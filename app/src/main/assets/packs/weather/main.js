// 示例工具包：天气。数据来自 Open-Meteo（免费、不用 Key）。
// 演示 kavi.http 联网、kavi.store 记住上次查的城市、抛错把原因告诉 Kavi。

const CODES = {
  0: "晴", 1: "基本晴", 2: "多云", 3: "阴",
  45: "雾", 48: "雾凇",
  51: "小毛毛雨", 53: "毛毛雨", 55: "大毛毛雨", 56: "冻毛毛雨", 57: "冻毛毛雨",
  61: "小雨", 63: "中雨", 65: "大雨", 66: "冻雨", 67: "冻雨",
  71: "小雪", 73: "中雪", 75: "大雪", 77: "雪粒",
  80: "阵雨", 81: "阵雨", 82: "强阵雨", 85: "阵雪", 86: "强阵雪",
  95: "雷阵雨", 96: "雷阵雨伴冰雹", 99: "强雷阵雨伴冰雹",
};

const sky = (c) => CODES[c] ?? `天气代码 ${c}`;

/** 城市名 → 经纬度。查过的存起来，下次不用再查。 */
async function locate(city, kavi) {
  const name = (city ?? "").trim() || kavi.store.get("lastCity");
  if (!name) throw new Error("要告诉我查哪个城市");
  const cache = kavi.store.get("places", {});
  let place = cache[name];
  if (!place) {
    const r = await kavi.http.get("https://geocoding-api.open-meteo.com/v1/search", {
      query: { name, count: 1, language: "zh", format: "json" },
    });
    if (!r.ok) throw new Error("查城市失败：HTTP " + r.status);
    const hit = (r.json().results ?? [])[0];
    if (!hit) throw new Error(`没找到「${name}」这个地方，换个写法试试（比如加上省份）`);
    place = {
      label: [hit.name, hit.admin1, hit.country].filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join("，"),
      lat: hit.latitude, lon: hit.longitude, tz: hit.timezone ?? "auto",
    };
    cache[name] = place;
    kavi.store.set("places", cache);
  }
  kavi.store.set("lastCity", name);
  return place;
}

export async function now({ city }, kavi) {
  const p = await locate(city, kavi);
  const r = await kavi.http.get("https://api.open-meteo.com/v1/forecast", {
    query: {
      latitude: p.lat, longitude: p.lon, timezone: p.tz, forecast_days: 1,
      current: "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m",
      daily: "temperature_2m_max,temperature_2m_min,precipitation_probability_max",
    },
  });
  if (!r.ok) throw new Error("查天气失败：HTTP " + r.status);
  const d = r.json();
  const c = d.current, day = d.daily;
  return [
    `${p.label}（当地时间 ${c.time.replace("T", " ")}）`,
    `${sky(c.weather_code)}，${c.temperature_2m}°C，体感 ${c.apparent_temperature}°C`,
    `湿度 ${c.relative_humidity_2m}%，风速 ${c.wind_speed_10m} km/h`,
    `今天 ${day.temperature_2m_min[0]}～${day.temperature_2m_max[0]}°C，降水概率 ${day.precipitation_probability_max[0] ?? "—"}%`,
  ].join("\n");
}

export async function forecast({ city, days }, kavi) {
  const n = Math.min(14, Math.max(1, Math.round(days ?? 5)));
  const p = await locate(city, kavi);
  const r = await kavi.http.get("https://api.open-meteo.com/v1/forecast", {
    query: {
      latitude: p.lat, longitude: p.lon, timezone: p.tz, forecast_days: n,
      daily: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
    },
  });
  if (!r.ok) throw new Error("查预报失败：HTTP " + r.status);
  const d = r.json().daily;
  const week = "日一二三四五六";
  const lines = d.time.map((t, i) => {
    const w = week[new Date(t + "T00:00:00").getDay()];
    return `${t.slice(5)} 周${w}  ${sky(d.weather_code[i])}  ${d.temperature_2m_min[i]}～${d.temperature_2m_max[i]}°C  降水 ${d.precipitation_probability_max[i] ?? "—"}%`;
  });
  return `${p.label} 未来 ${n} 天：\n` + lines.join("\n");
}
