package com.scaleguard.server.http.router;

import java.util.List;

public interface SystemLoader {

  List<SourceSystem> loadSources();
  List<TargetSystem> loadTargets();

}
