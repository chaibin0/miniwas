package server;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import server.enums.ContentType;
import server.enums.RequestLifeCycleListenerType;
import server.enums.StateCode;
import server.log.MyLogger;
import servlet.FilterChain;
import servlet.ServletConfig;
import servlet.ServletContext;
import servlet.ServletRequest;
import servlet.ServletResponse;
import servlet.http.HttpServletRequest;
import servlet.http.HttpServletResponse;

/**
 * 웹 서버 컨테이너로너 싱글톤 클래스이다. 클라이언트로부터 요청이 올 경우 처리하고 다시 데이터를 전송하는 역할을 한다.
 */
public class Container {

  private static final MyLogger logger = MyLogger.getLogger();

  private static Container container;

  private MappingInfo mappingInfo;

  private ServletContext context;

  private Map<Class<?>, Object> loadedServlet;

  private Map<Class<?>, Object> loadedFilter;

  private Map<String, HttpSessionImpl> sessionData;

  private ListenerContainer listenerContainer;

  /**
   * 컨테이너 처음 초기화하기 위한 생성자. private 생성자로 초기화할 수 없다.
   */
  private Container() {

    loadedFilter = new HashMap<>();
    loadedServlet = new HashMap<>();
    mappingInfo = new MappingInfo();
    sessionData = new HashMap<>();
    listenerContainer = ListenerContainer.getListenerContainer();
  }

  /**
   * 컨테이너는 싱글톤이다 유일한 웹 컨테이너 객체만 반환하고 만약 존재하지 않으면 웹 컨테이너를 생성한다.
   * 
   * @return container 서블릿 컨테이너 객체
   */
  public static Container getInstance() {

    if (container == null) {
      container = new Container();
    }
    return container;
  }

  /**
   * 클라이언트로부터 요청이 들어올 경우 requestHttp 메소드에서 처리한다. ServletRequestListener가 등록되어 있을 경우 요청 받을 때마다 리스너가
   * 실행된다.
   * 
   * @param clientSocket 클라이언트 소켓
   * @throws IOException IOException
   * @throws NoSuchElementException NoSuchElementException { if 서블릿이 존재하지 않을 경우 발생 }
   * @throws InterruptedException InterruptedException
   */
  public void requestHttp(Socket clientSocket)
      throws IOException, NoSuchElementException, InterruptedException {

    // parsed http message
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

      HttpParsedRequest parsedRequest = new HttpParsedRequest(reader);
      ServletRequest request = new Request(clientSocket, parsedRequest);
      ServletResponse response = new Response(clientSocket, parsedRequest);
      String url = parsedRequest.getUrl();

      // 요청 리스너
      listenerContainer.eventServletRequest(RequestLifeCycleListenerType.INIT, request);

      if (url != null && !url.isEmpty()) {
        dispatch(url, request, response);
      }

    } catch (IOException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());

    }
  }


  /**
   * 요청된 url를 기반으로 서블릿일 경우 executeServlet을 실행하고 웹어플리케이션 리소스나 해당 프로젝트에 존재하는 파일일 경우 sendResource에서
   * 실행한다. 어떠한 파일을 찾기 못할 경우 404에러로 클라이언트에게 전송한다.
   * 
   * @param url 요청받은 url-pattern
   * @param request 서블릿일 경우 HttpServletRequest 객체를 생성한다.
   * @param response HttpServletResponse 객체를 통해 출력 스트림을 사용한다.
   */
  public void dispatch(String url, ServletRequest request, ServletResponse response) {

    Runnable runnable = () -> {
      if (mappingInfo.containsServletPattern(url)) {
        if (containsFilter(url)) {
          executeFilter(url, request, response);
        } else {
          executeServlet(url, request, response);
        }
      } else if (Files.exists(Paths.get("webapps" + urlToPath(url)))) { // 해당프로젝트 파일 존재
        sendResource(Paths.get("webapps" + urlToPath(url)), response);
      } else if (Files.exists(Paths.get("resources" + urlToPath(url)))) { // WAS 안에 리소스 파일 존재
        sendResource(Paths.get("resources" + urlToPath(url)), response);
      } else {
        sendError(StateCode.NOT_FOUND, response);
      }
    };

    Thread thread = new Thread(runnable);
    thread.run();

  }


  /**
   * 에러코드에 관한 MyWebApplicationServer의 기본 에러페이지를 전송한다.
   * 
   * @param sc 상태 코드
   * @param response HttpServletResponse 객체
   */
  public void sendError(StateCode sc, ServletResponse response) {

    HttpServletResponse res = (HttpServletResponse) response;

    res.setStatus(sc.getStateCode());
    res.setHeader("Content-Type", ContentType.HTML.getMime());
    Path path = Paths.get("resources\\" + urlToPath(String.valueOf(sc.getStateCode()) + ".html"));
    sendTextFile(path, res);
  }

  /**
   * url에에 관해서 필터가 존재하는지 확인하는 메소드. 필터가 존재하지 않을 경우 필터를 만들지 않고 즉시 서블릿을 실행하게 한다.
   * 
   * @param url 필터가 적용될 수 있는 url
   * @return 필터가 존재하면 true, 그렇지 않으면 false
   */
  public boolean containsFilter(String url) {

    if (container.getMappingInfo().getFilterPattern(url).isEmpty()) {
      return false;
    }
    return true;
  }

  /**
   * Window 웹서버에 맞게 주소를 변환한다.
   * 
   * @param url 요청받은 url코드
   * @return 변환된 url 코드
   */
  private String urlToPath(String url) {

    url.replace('/', '\\');
    return url;
  }

  /**
   * 서블릿이 아닌 파일들을 클라이언트에게 전송한다. path 경로를 분석해서 contenType에 따라 다르게 전송한다.
   * 
   * @see Container#dispatch(String, HttpServletRequest, HttpServletResponse)
   */
  private void sendResource(Path path, ServletResponse response) {

    HttpServletResponse res = (HttpServletResponse) response;
    // URL 분석
    String extension = getExtension(path.toString());
    ContentType contentType = ContentType.fromString(extension);
    // contentype 설정
    res.setHeader("Content-Type", contentType.getMime());
    switch (contentType) {
      case JPG:
      case ICO:
      case PNG:
      case GIF:
        sendImageFile(path, res);
        break;

      default:
        sendTextFile(path, res);

    }
  }

  /**
   * 이미지 파일일 경우 BufferedOutputStream을 통해 전송한다. 이미지 데이터는 Content-length헤더를 통해 데이터의 길이를 전송한다.
   * 
   */
  private synchronized void sendImageFile(Path path, HttpServletResponse response) {

    response.setHeader("Content-length", String.valueOf(path.toFile().length()));

    try (InputStream inputstream = new FileInputStream(path.toFile())) {

      BufferedOutputStream outputStream = new BufferedOutputStream(response.getOutputStream());

      int content;
      while ((content = inputstream.read()) != -1) {
        outputStream.write(content);
      }
      outputStream.flush();
    } catch (IOException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());

    }

  }

  /**
   * 텍스트 파일일 경우 BufferedReader스트림을 통해 전송한다.
   */
  private synchronized void sendTextFile(Path path, HttpServletResponse response) {

    // 데이터 전송
    try (BufferedReader input =
        new BufferedReader(new InputStreamReader(new FileInputStream(path.toString())))) {

      PrintWriter writer = response.getWriter();
      String line = "";
      while ((line = input.readLine()) != null) {
        writer.print(line);
      }
      writer.flush();

    } catch (IOException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());

    }
  }

  /**
   * url 경로에 대한 확장자를 구한다.
   */
  private String getExtension(String path) {

    String extension = "";
    StringTokenizer ext = new StringTokenizer(path, ".");
    while (ext.hasMoreTokens()) {
      extension = ext.nextToken();
    }
    return extension;
  }


  /**
   * url에 매핑되는 filter와 filterChain을 만들고 서블릿보다 먼저 실행한다.
   * 
   * @param url 요청받은 url
   * @param request servletRequest 객체
   * @param response servletResponse 객체
   */
  public void executeFilter(String url, ServletRequest request, ServletResponse response) {

    try {

      FilterImpl filter = new FilterImpl(url);
      FilterChain chain = filter.makeFilterChain();
      filter.doFilter(request, response, chain);

    } catch (IOException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());

    }
  }

  /**
   * 요청 받은 url이 서블릿일 경우 HttpServletRequest와 HttpServletResponse를 가져와서 요청한 servlet을 수행한다.
   * 
   * @param url url
   */
  public void executeServlet(String url, ServletRequest request, ServletResponse response) {

    try {

      HttpServletRequest req = (HttpServletRequest) request;
      HttpServletResponse res = (HttpServletResponse) response;

      String servletName = mappingInfo.getServletName(url);
      MappingServlet mappingServlet = mappingInfo.getServletClassType(servletName);
      Class<?> servlet = Class.forName(mappingServlet.getServletClassName());
      Object instance = getServletClass(servlet, servletName);
      invokeServiceMethod(instance, servlet, req, res);

    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());

    }

  }

  /**
   * service() 메소드를 invoke한다.
   */
  private void invokeServiceMethod(Object instance, Class<?> servlet, HttpServletRequest request,
      HttpServletResponse response) {

    try {

      Object[] param = {request, response};
      Class<?>[] typeParam =
          {servlet.http.HttpServletRequest.class, servlet.http.HttpServletResponse.class};

      Method method = servlet.getSuperclass().getDeclaredMethod("service", typeParam);
      method.invoke(instance, param);

    } catch (NoSuchMethodException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (SecurityException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (IllegalAccessException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (InvocationTargetException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    }

  }

  /**
   * 서블릿이 한번도 로드하지 않았으면 서블릿을 인스턴스화 시킨다.
   * 
   */
  private Object getServletClass(Class<?> servlet, String servletName) {

    if (!loadedServlet.containsKey(servlet)) {
      Object instance = createNewInstance(servlet, servletName);
      invokeInit(instance, servlet, servletName);
      return instance;
    }

    return loadedServlet.get(servlet);
  }

  /**
   * 서블릿 인스턴스화가 되었을 경우 init()메소드를 실행해서 servletConfig를 초기화한다.
   * 
   */
  private void invokeInit(Object instance, Class<?> servlet, String servletName) {

    try {

      logger.log("servlet init : " + servletName);
      Class<?>[] typeParam = {servlet.ServletConfig.class};
      Method init = servlet.getSuperclass().getSuperclass().getDeclaredMethod("init", typeParam);
      ServletConfig config = mappingInfo.getServletConfig(servletName);
      init.invoke(instance, config);

    } catch (NoSuchMethodException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (SecurityException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (IllegalAccessException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (InvocationTargetException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    }

  }

  /**
   * 서블릿을 인스턴스화하고 loadedClass에 인스턴스한 객체를 저장한다.
   */
  private Object createNewInstance(Class<?> servlet, String servletName) {

    Object instance = null;
    try {

      instance = servlet.newInstance();
      loadedServlet.put(servlet, instance);

    } catch (InstantiationException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (IllegalAccessException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (SecurityException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
      logger.errorLog(e.getStackTrace());
    }
    return instance;

  }

  /**
   * MappingInfo 객체를 반환하는 메소드.
   */
  public MappingInfo getMappingInfo() {

    return mappingInfo;
  }

  /**
   * ServletContext를 반환하는 메소드.
   */
  public ServletContext getServletContext() {

    return context;
  }

  /**
   * 세션 클래스를 반환하는 메소드.
   */
  public HttpSessionImpl getSession(String name) {

    return sessionData.get(name);
  }

  /**
   * 세션 아이디를 컨테이너에 저장하는 메소드.
   * 
   * @param jsessionid 세션 아이디
   * @param session 세션클래스
   */
  public void setSession(String jsessionid, HttpSessionImpl session) {

    sessionData.put(jsessionid, session);
  }

  /**
   * JESSESIONID가 존재하고 있는지 확인하는 메소드.
   * 
   * @param sessionId JESSIONID
   * @return 존재하면 true, 그렇지 않으면 false를 반환
   */
  public boolean containsSession(String sessionId) {

    return sessionData.containsKey(sessionId);
  }


  /**
   * 인스턴스화된 필터가 존재하는지 확인한다.
   * 
   * @param filter 필터 클래스 타입
   * @return 인스턴스화된 필터가 있으면 true, 그렇지 않으면 false를 반환
   */
  public boolean containsLoadedFilter(Class<?> filter) {

    return loadedFilter.containsKey(filter);
  }


  /**
   * 인스턴스화가 되어있는 필터 객체를 반환한다.
   * 
   * @param filter filter type
   * @return filter object
   */
  public Object getLoadedFilter(Class<?> filter) {

    return loadedFilter;
  }

  /**
   * ServletContext를 초기화한다.
   */
  public void initServletContext() {

    context = new ServletContextImpl();

    Map<String, String> contextParam = mappingInfo.getServletContextInitParam();
    for (String name : contextParam.keySet()) {
      context.setInitParameter(name, contextParam.get(name));
    }
  }

  /**
   * 모든 리스너는 인터페이스에 적용된 리스너에 따라 리스너 클래스 타입을 저장한다.
   */
  public void initListener() {

    try {
      List<String> list = mappingInfo.getListener();
      for (String listenerName : list) {
        Class<?> listenerClass = Class.forName(listenerName);
        listenerContainer.addListener(listenerClass, listenerClass.getInterfaces());
      }
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
  }
}
