package org.sp001.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public city-centre geocoding and dated forecast. Public edition requires an explicitly supplied city; no family location is embedded. */
public final class CityWeather {
    public static final String CITY="", ZONE="Asia/Shanghai";
    private static final Semaphore SLOTS=new Semaphore(2);
    private static final OkHttpClient CLIENT;
    static {
        ThreadPoolExecutor threads=new ThreadPoolExecutor(0,2,15L,TimeUnit.SECONDS,new SynchronousQueue<Runnable>(),new ThreadFactory(){public Thread newThread(Runnable work){Thread t=new Thread(work,"SP001-Weather");t.setDaemon(true);return t;}});
        Dispatcher dispatcher=new Dispatcher(threads);dispatcher.setMaxRequests(2);dispatcher.setMaxRequestsPerHost(2);
        CLIENT=WeatherTls.configure(new OkHttpClient.Builder()).dispatcher(dispatcher).proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).connectTimeout(5,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).writeTimeout(5,TimeUnit.SECONDS).build();
    }
    private CityWeather(){}
    public static boolean supports(String city){return false;}

    public static JSONObject fetch(int daysAhead,long wallMs,final MiniMaxVoiceClient.Cancel token)throws Exception{return fetch(CITY,daysAhead,wallMs,token);}
    public static JSONObject fetch(String city,int daysAhead,long wallMs,final MiniMaxVoiceClient.Cancel token)throws Exception{
        if(daysAhead<0||daysAhead>6||wallMs<0)throw new IOException("WEATHER_DAY_RANGE");
        if(token==null||token.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("TURN_CANCELLED");
        JSONObject place;
        if(supports(city))place=home();
        else{
            String name=cityName(city),language=name.matches("[A-Za-z .'-]+")?"en":"zh";
            JSONObject geo=readJson("https://geocoding-api.open-meteo.com/v1/search?name="+java.net.URLEncoder.encode(name,"UTF-8")+"&count=10&language="+language+"&format=json",token);
            place=selectLocation(geo,name);
        }
        JSONObject raw=readJson(forecastUrl(place),token);
        return parseAt(raw,wallMs,daysAhead,place);
    }
    /** Absolute date supplied by a real model tool call, never inferred from user keywords. */
    public static JSONObject fetchDate(String city,String date,long wallMs,MiniMaxVoiceClient.Cancel token)throws Exception{
        if(date==null||!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IOException("WEATHER_DATE_INVALID");
        if(token==null||token.cancelled())throw new IOException("TURN_CANCELLED");
        JSONObject place;if(supports(city))place=home();else{String name=cityName(city);place=selectLocation(readJson("https://geocoding-api.open-meteo.com/v1/search?name="+java.net.URLEncoder.encode(name,"UTF-8")+"&count=10&language="+(name.matches("[A-Za-z .'-]+")?"en":"zh")+"&format=json",token),name);}
        int days=daysUntil(date,wallMs,place.getString("timezone"));JSONObject result=parseAt(readJson(forecastUrl(place),token),wallMs,days,place);result.put("daysAhead",days);return result;
    }
    public static int daysUntil(String date,long wallMs,String zone)throws Exception{
        if(date==null||!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!java.util.Arrays.asList(TimeZone.getAvailableIDs()).contains(zone))throw new IOException("WEATHER_DATE_INVALID");
        SimpleDateFormat local=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);local.setTimeZone(TimeZone.getTimeZone(zone));SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);iso.setLenient(false);iso.setTimeZone(TimeZone.getTimeZone("UTC"));long gap;try{gap=iso.parse(date).getTime()-iso.parse(local.format(new Date(wallMs))).getTime();}catch(Exception e){throw new IOException("WEATHER_DATE_INVALID");}
        if(gap<0||gap>6L*86400000L||gap%86400000L!=0)throw new IOException("WEATHER_DATE_UNAVAILABLE");return (int)(gap/86400000L);
    }
    private static JSONObject home()throws Exception{throw new IOException("WEATHER_CITY_REQUIRED");}
    static String cityName(String value)throws IOException{
        if(value==null)throw new IOException("WEATHER_CITY_REQUIRED");String s=value.trim().replaceFirst("^(?:中国|中國)","").replaceFirst("市$","");
        if(s.length()<2||s.length()>50||!s.matches("[\\p{L} .'-]+")||s.matches(".*(?:天气|明天|今天|查询|问题|城市|住址|小区|小區|号|號).*"))throw new IOException("WEATHER_CITY_INVALID");return s;
    }
    public static JSONObject selectLocation(JSONObject response,String requested)throws Exception{
        String name=cityName(requested);JSONArray results=response==null?null:response.optJSONArray("results");
        if(results==null||results.length()==0||response.optBoolean("error"))throw new IOException("WEATHER_CITY_NOT_FOUND");
        JSONObject best=null;int bestRank=-1;boolean ambiguous=false;
        for(int i=0;i<Math.min(10,results.length());i++){
            JSONObject p=results.optJSONObject(i);if(p==null)continue;String found=p.optString("name").replaceFirst("市$","");
            if(!name.equalsIgnoreCase(found))continue;String feature=p.optString("feature_code");
            int rank=feature.equals("PPLC")?4:feature.equals("PPLA")?3:feature.equals("PPLA2")?2:feature.startsWith("PPL")?1:0;if(rank==0)continue;
            if(rank>bestRank){best=p;bestRank=rank;ambiguous=false;}else if(rank==bestRank&&best!=null&&(Math.abs(best.optDouble("latitude")-p.optDouble("latitude"))>0.25||Math.abs(best.optDouble("longitude")-p.optDouble("longitude"))>0.25))ambiguous=true;
        }
        if(best==null)throw new IOException("WEATHER_CITY_NOT_FOUND");if(ambiguous)throw new IOException("WEATHER_AMBIGUOUS_CITY");
        String zone=best.optString("timezone");if(!java.util.Arrays.asList(TimeZone.getAvailableIDs()).contains(zone))throw new IOException("WEATHER_LOCATION_TIMEZONE");
        return new JSONObject().put("city",best.getString("name")).put("latitude",number(best,"latitude",-90,90)).put("longitude",number(best,"longitude",-180,180)).put("timezone",zone).put("country",best.optString("country_code"));
    }
    static String forecastUrl(JSONObject place)throws Exception{
        return "https://api.open-meteo.com/v1/forecast?latitude="+number(place,"latitude",-90,90)+"&longitude="+number(place,"longitude",-180,180)+"&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=7&timezone="+java.net.URLEncoder.encode(place.getString("timezone"),"UTF-8");
    }
    private static JSONObject readJson(String url,final MiniMaxVoiceClient.Cancel token)throws Exception{
        if(token==null||token.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("TURN_CANCELLED");
        WeatherTls.requireHost(url);
        if(!SLOTS.tryAcquire())throw new IOException("WEATHER_BUSY");
        final CountDownLatch complete=new CountDownLatch(1);
        final AtomicReference<JSONObject> data=new AtomicReference<JSONObject>();
        final AtomicReference<IOException> error=new AtomicReference<IOException>();
        final Call call=CLIENT.newCall(new Request.Builder().url(url).header("Accept","application/json").header("User-Agent","SP001-Home-Companion/1.0").get().build());
        boolean submitted=false;
        try{
            call.enqueue(new Callback(){
                public void onFailure(Call ignored,IOException cause){error.set(new IOException("WEATHER_NETWORK_FAILED",cause));SLOTS.release();complete.countDown();}
                public void onResponse(Call ignored,Response response){
                    try{
                        if(!response.isSuccessful())throw new IOException("WEATHER_HTTP_"+response.code());
                        if(response.body()==null||response.body().contentType()==null||!response.body().contentType().toString().contains("application/json"))throw new IOException("WEATHER_CONTENT_TYPE");
                        InputStream in=response.body().byteStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[2048];int n;
                        while((n=in.read(chunk))!=-1){if(token.cancelled())throw new IOException("TURN_CANCELLED");if(n>65536-bytes.size())throw new IOException("WEATHER_BODY_BOUND");bytes.write(chunk,0,n);}
                        data.set(new JSONObject(new String(bytes.toByteArray(),"UTF-8")));
                    }catch(Exception failure){error.set(failure instanceof IOException?(IOException)failure:new IOException("WEATHER_INVALID_DATA",failure));}
                    finally{response.close();SLOTS.release();complete.countDown();}
                }
            });submitted=true;
            long end=android.os.SystemClock.elapsedRealtime()+12000L;
            while(!complete.await(100,TimeUnit.MILLISECONDS)){
                if(token.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("TURN_CANCELLED");
                if(android.os.SystemClock.elapsedRealtime()>=end)throw new IOException("WEATHER_TIMEOUT");
            }
            if(token.cancelled())throw new IOException("TURN_CANCELLED");
            if(error.get()!=null)throw error.get();
            if(data.get()==null)throw new IOException("WEATHER_INVALID_DATA");return data.get();
        }finally{if(!submitted)SLOTS.release();if(complete.getCount()!=0)call.cancel();}
    }

    private static String conditions(int code){switch(code){case 0:return "晴";case 1:return "大部晴朗";case 2:return "多云";case 3:return "阴天";case 45:case 48:return "有雾";case 51:case 53:case 55:return "毛毛雨";case 61:case 63:case 65:return "下雨";case 71:case 73:case 75:case 77:return "下雪";case 80:case 81:case 82:return "阵雨";case 95:case 96:case 99:return "雷雨";default:return "天气可能变化";}}
    /** Deterministic rendering of validated weather: no invented hours, dates, warnings or future days. */
    public static String speech(JSONObject data,int daysAhead)throws Exception{
        if(!"verified_weather".equals(data.optString("kind"))||daysAhead<0||daysAhead>6)throw new IOException("WEATHER_EVIDENCE_REQUIRED");
        JSONObject now=data.getJSONObject("current"),day=data.getJSONObject("forecast");String date=day.getString("date"),city=data.getString("city");String label=Integer.parseInt(date.substring(5,7))+"月"+Integer.parseInt(date.substring(8,10))+"日";
        String range="最低约"+Math.round(day.getDouble("lowC"))+"度，最高约"+Math.round(day.getDouble("highC"))+"度，全天降雨概率是百分之"+Math.round(day.getDouble("rainProbabilityPercent"))+"。";
        if(daysAhead==0)return "查到了，"+city+"目前的天气数据是"+conditions(now.getInt("weatherCode"))+"，气温约"+Math.round(now.getDouble("temperatureC"))+"度，体感约"+Math.round(now.getDouble("feelsLikeC"))+"度。今天"+label+"的预报，"+range+"这只是天气预报，不代表一定会下雨，出门前再留意一下。";
        return (daysAhead==1?"明天":daysAhead==2?"后天":"")+label+"，"+city+"的预报是"+conditions(day.getInt("weatherCode"))+"，"+range+"降雨概率不代表一定下雨；出门可以带把伞。";
    }

    private static double number(JSONObject o,String key,double min,double max)throws Exception{
        Object v=o.opt(key);if(!(v instanceof Number))throw new IOException("WEATHER_MISSING_VALUE");
        double n=((Number)v).doubleValue();if(Double.isNaN(n)||Double.isInfinite(n)||n<min||n>max)throw new IOException("WEATHER_VALUE_BOUND");return n;
    }
    private static double element(JSONObject o,String key,int at,double min,double max)throws Exception{
        JSONArray values=o.optJSONArray(key);if(values==null||at>=values.length())throw new IOException("WEATHER_MISSING_FORECAST");
        return number(new JSONObject().put("v",values.get(at)),"v",min,max);
    }
    public static JSONObject parse(JSONObject raw,long wallMs,int daysAhead)throws Exception{return parseAt(raw,wallMs,daysAhead,home());}
    public static JSONObject parseAt(JSONObject raw,long wallMs,int daysAhead,JSONObject place)throws Exception{
        String zone=place.getString("timezone");if(!java.util.Arrays.asList(TimeZone.getAvailableIDs()).contains(zone))throw new IOException("WEATHER_LOCATION_TIMEZONE");
        if(raw==null||raw.optBoolean("error")||!zone.equals(raw.optString("timezone"))||raw.optInt("utc_offset_seconds",Integer.MIN_VALUE)!=TimeZone.getTimeZone(zone).getOffset(wallMs)/1000)throw new IOException("WEATHER_LOCATION_TIMEZONE");
        if(daysAhead<0||daysAhead>6||wallMs<0)throw new IOException("WEATHER_DAY_RANGE");
        if(Math.abs(number(raw,"latitude",-90,90)-number(place,"latitude",-90,90))>0.25||Math.abs(number(raw,"longitude",-180,180)-number(place,"longitude",-180,180))>0.25)throw new IOException("WEATHER_WRONG_CITY");
        JSONObject current=raw.getJSONObject("current"),daily=raw.getJSONObject("daily");
        if(!"°C".equals(raw.getJSONObject("current_units").optString("temperature_2m"))||!"°C".equals(raw.getJSONObject("daily_units").optString("temperature_2m_max")))throw new IOException("WEATHER_UNITS");
        SimpleDateFormat minute=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm",Locale.ROOT);minute.setLenient(false);minute.setTimeZone(TimeZone.getTimeZone(zone));if(!current.optString("time").matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}"))throw new IOException("WEATHER_INVALID_TIME");
        long sampleMs=minute.parse(current.getString("time")).getTime();if(wallMs-sampleMs>10800000L||sampleMs-wallMs>1800000L)throw new IOException("WEATHER_STALE_DATA");
        Calendar local=Calendar.getInstance(TimeZone.getTimeZone(zone));local.setTimeInMillis(wallMs);local.add(Calendar.DAY_OF_MONTH,daysAhead);
        SimpleDateFormat day=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);day.setTimeZone(TimeZone.getTimeZone(zone));String target=day.format(local.getTime());
        JSONArray dates=daily.getJSONArray("time");int index=-1;for(int i=0;i<Math.min(8,dates.length());i++)if(target.equals(dates.getString(i)))index=i;
        if(index<0)throw new IOException("WEATHER_TARGET_DATE_MISSING");
        double min=element(daily,"temperature_2m_min",index,-70,60),max=element(daily,"temperature_2m_max",index,-70,60);if(min>max)throw new IOException("WEATHER_MIN_MAX");
        JSONObject now=new JSONObject().put("asOfLocal",current.getString("time")).put("temperatureC",number(current,"temperature_2m",-70,60)).put("feelsLikeC",number(current,"apparent_temperature",-80,80)).put("precipitationMm",number(current,"precipitation",0,500)).put("windKmh",number(current,"wind_speed_10m",0,350)).put("weatherCode",number(current,"weather_code",0,99));
        JSONObject forecast=new JSONObject().put("date",target).put("lowC",min).put("highC",max).put("rainProbabilityPercent",element(daily,"precipitation_probability_max",index,0,100)).put("weatherCode",element(daily,"weather_code",index,0,99));
        return new JSONObject().put("kind","verified_weather").put("city",place.getString("city")).put("timezone",zone).put("queriedAtMs",wallMs).put("current",now).put("forecast",forecast)
            .put("sources",new JSONArray().put(new JSONObject().put("title","Open-Meteo 数值天气预报").put("url","https://open-meteo.com/").put("dataTime",current.getString("time"))))
            .put("guidance","数据来自数值天气模型，不是家门口气象站实测。只使用返回数字，可四舍五入到整数。区分当前和目标日期，概率不是肯定会下雨；没有预警数据不能说无预警或编台风。WMO代码0晴，1大部晴朗，2多云，3阴，45/48雾，51/53/55毛毛雨，61/63/65雨，80/81/82阵雨，95/96/99雷雨；其他代码不确定就不强说。给适龄的穿着/带伞提示即可，不诱导危险户外活动。");
    }
}
