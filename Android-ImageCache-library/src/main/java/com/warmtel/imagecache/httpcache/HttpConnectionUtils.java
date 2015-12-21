package com.warmtel.imagecache.httpcache;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.protocol.HTTP;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

/**
 * 从网络获取数据工具类
 * 1.异步操作
 * 2.实现HTTP网络连接
 * 3.Get和Post请求，参数
 * 4.接口回调，回传响应结果数据
 * 5.数据缓存
 * 6.响应错回调
 */
public class HttpConnectionUtils {
    private Context mContext;

    public enum Method {
        GET, POST
    }

    // 实现接口回调 第一步定义接口
    public interface CallConnectionInterface {
        void onExecuteResponse(String response);

        void onErrorResponse(String errorResponse);

        void onCacheResponse(String errorResponse, String cacheData);
    }

    // 实现接口回调 第二步 声明接口，定义注册接口入口
    public CallConnectionInterface mCallConnectionInterface;

    public void setonCallConnectionInterface(
            CallConnectionInterface callConnectionInterface) {
        mCallConnectionInterface = callConnectionInterface;
    }

    private static HttpConnectionUtils sConnetionUtils = null;

    public static HttpConnectionUtils getInstance(Context context) {
        if (sConnetionUtils == null) {
            sConnetionUtils = new HttpConnectionUtils(context);
        }
        return sConnetionUtils;
    }

    private HttpConnectionUtils() {
    }

    /**
     * 缓存需实现此构造方法
     *
     * @param context
     */
    private HttpConnectionUtils(Context context) {
        mContext = context;
    }

    private HttpConnectionUtils(CallConnectionInterface callConnectionInterface) {
        mCallConnectionInterface = callConnectionInterface;
    }

    /**
     * 无参数无缓存
     *
     * @param url
     * @param method
     * @param callConnectionInterface
     */
    public void asyncTaskConnection(final String url, final Method method,
                                    CallConnectionInterface callConnectionInterface) {
        asyncTaskConnection(url, null, method, null, callConnectionInterface);
    }

    /**
     * 无参数有缓存
     *
     * @param url
     * @param method
     * @param cacheKey
     * @param callConnectionInterface
     */
    public void asyncTaskConnection(final String url, final Method method, final String cacheKey,
                                    CallConnectionInterface callConnectionInterface) {
        asyncTaskConnection(url, null, method, cacheKey, callConnectionInterface);
    }

    /**
     * 有参数不缓存
     *
     * @param url
     * @param parameterMap
     * @param method
     * @param callConnectionInterface
     */
    public void asyncTaskConnection(final String url, final HashMap<String, String> parameterMap, final Method method,
                                    CallConnectionInterface callConnectionInterface) {
        asyncTaskConnection(url, parameterMap, method, null, callConnectionInterface);
    }

    /**
     * 有参数有缓存
     *
     * @param url
     * @param parameterMap
     * @param method
     * @param callConnectionInterface
     */
    public void asyncTaskConnection(final String url,
                                    final HashMap<String, String> parameterMap, final Method method, final String cacheKey,
                                    CallConnectionInterface callConnectionInterface) {

        mCallConnectionInterface = callConnectionInterface;
        new AsyncTask<Void, Void, String>() {
            String errorMsg = "";

            protected String doInBackground(Void... params) {
                String result;
                try {
                    result = getHttpConnection(url, parameterMap, method, cacheKey);
                } catch (MessageException e) {
                    e.printStackTrace();
                    result = null;
                    errorMsg = e.getMessage();
                }
                return result;

            }

            @Override
            protected void onPostExecute(String result) {
                super.onPostExecute(result);
                // ====第三步 调用接口方法
                if (result != null) {
                    mCallConnectionInterface.onExecuteResponse(result);
                } else if (mContext != null && cacheKey != null) {
                    mCallConnectionInterface.onCacheResponse(errorMsg, CachePreference.getInstance(mContext).getCache(cacheKey));
                } else {
                    mCallConnectionInterface.onErrorResponse(errorMsg);
                }
            }
        }.execute();

    }

    public String getHttpConnection(String url,
                                    HashMap<String, String> parameterMap, Method httpMethod, String cacheKey) throws MessageException {

        String content = "";
        try {
            HttpClient httpClient = new DefaultHttpClient();
            //请求超时
            httpClient.getParams().setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 10000);
            //读取超时
            httpClient.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 15000);
            HttpUriRequest request = getHttpUrlRequest(url, parameterMap, httpMethod);
            HttpResponse response = httpClient.execute(request);

            int statusCode = response.getStatusLine().getStatusCode();
            Log.v("tag", "getHttpConnection  statusCode  :" + statusCode);

            switch (statusCode) {
                case 200:
                    // String content = EntityUtils.toString(response.getEntity());
                    content = readIt(response.getEntity().getContent());

                    if (mContext != null && cacheKey != null) {
                        Log.e("tag", "写缓存>>>>");
                        CachePreference.getInstance(mContext).putCache(cacheKey, content);
                    }
                    break;
                default:
                    throw new MessageException("服务器异常!");
            }
        } catch (IOException e) {
            throw new MessageException("连接服务器异常，请检查网络连接！");
        }
        return content;
    }

    public HttpUriRequest getHttpUrlRequest(String url,
                                            HashMap<String, String> parameterMap, Method method)
            throws ClientProtocolException, IOException {
        if (method == Method.POST) {

            HttpPost httpPost = new HttpPost(url);
            // =========================组装Post参数====================
            if (parameterMap != null) {
                ArrayList<BasicNameValuePair> parameters = new ArrayList<BasicNameValuePair>();

                Set<String> set = parameterMap.keySet();
                Iterator<String> iterator = set.iterator();
                while (iterator.hasNext()) {
                    String key = iterator.next();
                    String name = parameterMap.get(key);
                    System.out.println("key " + key + "name :" + name);

                    BasicNameValuePair namePair = new BasicNameValuePair(key,
                            name);
                    parameters.add(namePair);
                }

                UrlEncodedFormEntity entity = new UrlEncodedFormEntity(
                        parameters, HTTP.UTF_8);

                httpPost.setEntity(entity);
            }
            httpPost.setHeader("Content-Type",
                    "application/x-www-form-urlencoded; charset=utf-8");

            return httpPost;

        } else {
            if (parameterMap != null) {
                // ================组装Get请求参数===============================
                // String url =
                // "http://192.168.1.203/web1502/login_servle?userName=张三&passWord=123";

                if (url.indexOf("?") < 0) {
                    url = url + "?";
                }

                Set<String> set = parameterMap.keySet();
                Iterator<String> iterator = set.iterator();
                while (iterator.hasNext()) {
                    String key = iterator.next();
                    String name = parameterMap.get(key);
                    System.out.println("key " + key + "name :" + name);

                    url = url + "&" + key + "=" + name;
                }

                Log.v("tag", "URL :" + url);
                // ================组装Get请求参数===============================
            }

            HttpGet httpGet = new HttpGet(url);

            return httpGet;
        }
    }

    /**
     * 将InputStream转换成String返回
     *
     * @param stream
     * @return
     * @throws IOException
     * @throws UnsupportedEncodingException
     */
    public String readIt(InputStream stream) throws IOException,
            UnsupportedEncodingException {
        Reader reader = new InputStreamReader(stream, "UTF-8");
        // 创建包装流
        BufferedReader br = new BufferedReader(reader);
        // 定义String类型用于储存单行数据
        String line = null;
        // 创建StringBuffer对象用于存储所有数据
        StringBuffer sb = new StringBuffer();
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }

        return sb.toString();

    }
}
