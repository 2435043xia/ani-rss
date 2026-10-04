package ani.rss.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TmdbAlignChoice {
    private Integer seasonNumber;
    private Integer episodeNumber;
    private String airDate;
    private String episodeName;
    private Integer newSeason;
    private Integer newOffset;
    private String sampleFileName;
    private String fallbackPath;
    private Boolean seasonConflict;
    private String conflictWith;
}
