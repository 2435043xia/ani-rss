package ani.rss.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class EmbyPathChoice {
    private String embyHost;
    private String seriesName;
    private String tmdbId;
    private String originalPath;
    private String replacedPath;
}
