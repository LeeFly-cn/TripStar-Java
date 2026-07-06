package com.zkry.map.service;

import com.zkry.map.dto.MapCityRequest;
import com.zkry.map.dto.MapPlanningContext;
import java.util.List;

public interface MapContextService {

    MapPlanningContext collect(List<MapCityRequest> cityRequests);
}
