package io.modsh.core.readline;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public interface Function extends Action {

  /**
   * @return the action name
   */
  String getName();

}