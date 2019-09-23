package servlet.http;

import java.io.IOException;
import servlet.RequestDispatcher;
import servlet.ServletRequest;

public interface HttpServletRequest extends ServletRequest {

  String getMethod() throws IOException;

  public RequestDispatcher getRequestDispatcher(String path);

}