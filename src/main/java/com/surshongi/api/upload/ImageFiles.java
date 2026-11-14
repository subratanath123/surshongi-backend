package com.surshongi.api.upload;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import java.io.InputStream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.stereotype.Component;

/** Profile photos when the uploads folder cannot be written. */
@Component
public class ImageFiles {
  private final GridFSBucket bucket;

  public ImageFiles(MongoDatabaseFactory databases) {
    this.bucket = GridFSBuckets.create(databases.getMongoDatabase(), "images");
  }

  public void save(String name, String contentType, InputStream body) {
    GridFSUploadOptions options = new GridFSUploadOptions().metadata(new Document("contentType", contentType));
    bucket.uploadFromStream(name, body, options);
  }

  public GridFSFile find(String name) {
    return bucket.find(Filters.eq("filename", name)).first();
  }

  public InputStream open(ObjectId id, long start) {
    GridFSDownloadStream stream = bucket.openDownloadStream(id);
    long left = start;
    while (left > 0) {
      long skipped = stream.skip(left);
      if (skipped <= 0) break;
      left -= skipped;
    }
    return stream;
  }
}
