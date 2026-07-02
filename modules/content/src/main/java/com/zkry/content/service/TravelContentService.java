package com.zkry.content.service;

import com.zkry.content.dto.ContentCityRequest;
import com.zkry.content.dto.ContentPlanningContext;
import java.util.List;

public interface TravelContentService {

    ContentPlanningContext collect(List<ContentCityRequest> requests);

    String photo(String keyword);
}
