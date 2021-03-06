/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.core.service.openshift;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.maven.core.util.KubernetesResourceUtil;
import io.fabric8.maven.core.util.ResourceFileType;
import io.fabric8.maven.docker.util.ImageName;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.openshift.api.model.*;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.utils.Strings;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.plugin.MojoExecutionException;

/**
 * Handler object for managing image streams
 *
 * @author roland
 * @since 16/01/17
 */
public class ImageStreamService {


    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    private final OpenShiftClient client;
    private final Logger log;

    /**
     * Retry parameter
     */
    private final int IMAGE_STREAM_TAG_RETRIES = 15;
    private final long IMAGE_STREAM_TAG_RETRY_TIMEOUT_IN_MILLIS = 1000;


    public ImageStreamService(OpenShiftClient client, Logger log) {
        this.client = client;
        this.log = log;
    }

    /**
     * Save the images stream to a file
     * @param imageName name of the image for which the stream should be extracted
     * @param target file to store the image stream
     */
    public void appendImageStreamResource(ImageName imageName, File target) throws MojoExecutionException {
        String tag = Strings.isNullOrBlank(imageName.getTag()) ? "latest" : imageName.getTag();
        try {
            ImageStream is = new ImageStreamBuilder()
                    .withNewMetadata()
                    .withName(imageName.getSimpleName())
                    .endMetadata()

                    .withNewSpec()
                    .addNewTag()
                      .withName(tag)
                      .withNewFrom().withKind("ImageStreamImage").endFrom()
                    .endTag()
                    .endSpec()

                    .build();
            createOrUpdateImageStreamTag(client, imageName, is);
            File fullTargetFile = appendImageStreamToFile(is, target);
            log.info("ImageStream %s written to %s", imageName.getSimpleName(), fullTargetFile);
        } catch (KubernetesClientException e) {
            KubernetesResourceUtil.handleKubernetesClientException(e, this.log);
        } catch (IOException e) {
            throw new MojoExecutionException(String.format("Cannot write ImageStream descriptor for %s to %s : %s",
                                                           imageName.getFullName(), target.getAbsoluteFile(), e.getMessage()),e);
        }
    }

    private File appendImageStreamToFile(ImageStream is, File target) throws MojoExecutionException, IOException {

        Map<String, ImageStream> imageStreams = readAlreadyExtractedImageStreams(target);
        // Override with given image stream
        imageStreams.put(is.getMetadata().getName(),is);

        KubernetesList isList =
            new KubernetesListBuilder().withItems(new ArrayList<HasMetadata>(imageStreams.values())).build();
        return writeImageStreams(target, isList);
    }

    private File writeImageStreams(File target, KubernetesList entity) throws MojoExecutionException, IOException {
        final File targetWithoutExt;
        final ResourceFileType type;
        String ext = "";
        try {
            ext = FilenameUtils.getExtension(target.getPath());
            type = ResourceFileType.fromExtension(ext);
            String p = target.getAbsolutePath();
            targetWithoutExt = new File(p.substring(0,p.length() - ext.length() - 1));
        } catch (IllegalArgumentException exp) {
            throw new MojoExecutionException(
                String.format("Invalid extension '%s' for ImageStream target file '%s'. Allowed extensions: yml, json", ext, target.getPath()), exp);
        }
        return KubernetesResourceUtil.writeResource(entity, targetWithoutExt, type);
    }

    private Map<String, ImageStream> readAlreadyExtractedImageStreams(File target) throws IOException {
        // If it already exists, read in the file and use it for update
        Map<String, ImageStream> imageStreams = new HashMap<>();
        if (target.length() > 0) {
            for (HasMetadata entity : KubernetesResourceUtil.loadResources(target)) {
                if ("ImageStream".equals(KubernetesHelper.getKind(entity))) {
                    imageStreams.put(entity.getMetadata().getName(), (ImageStream) entity);
                }
                // Ignore all other kind of entities. There shouldn't be any included anyway
            }
        }
        return imageStreams;
    }

    private void createOrUpdateImageStreamTag(OpenShiftClient client, ImageName image, ImageStream is) throws MojoExecutionException {
        String namespace = client.getNamespace();
        String tagSha = findTagSha(client, image.getSimpleName(), client.getNamespace());
        String name = image.getSimpleName() + "@" + tagSha;

        TagReference tag = extractTag(is);
        ObjectReference from = extractFrom(tag);

        if (!Objects.equals(image.getTag(), tag.getName())) {
            tag.setName(image.getTag());
        }
        if (!Objects.equals("ImageStreamImage", from.getKind())) {
            from.setKind("ImageStreamImage");
        }
        if (!Objects.equals(namespace, from.getNamespace())) {
            from.setNamespace(namespace);
        }
        if (!Objects.equals(name, from.getName())) {
            from.setName(name);
        }
    }

    private ObjectReference extractFrom(TagReference tag) {
        ObjectReference from = tag.getFrom();
        if (from == null) {
            from = new ObjectReference();
            tag.setFrom(from);
        }
        return from;
    }

    private TagReference extractTag(ImageStream is) {
        ImageStreamSpec spec = is.getSpec();
        if (spec == null) {
            spec = new ImageStreamSpec();
            is.setSpec(spec);
        }
        List<TagReference> tags = spec.getTags();
        if (tags == null) {
            tags = new ArrayList<>();
            spec.setTags(tags);
        }
        TagReference tag = null;
        if (tags.isEmpty()) {
            tag = new TagReference();
            tags.add(tag);
        } else {
            tag = tags.get(tags.size() - 1);
        }
        return tag;
    }

    private String findTagSha(OpenShiftClient client, String imageStreamName, String namespace) throws MojoExecutionException {
        ImageStream currentImageStream = null;

        for (int i = 0; i < IMAGE_STREAM_TAG_RETRIES; i++) {
            if (i > 0) {
                log.info("Retrying to find tag on ImageStream %s", imageStreamName);
                try {
                    Thread.sleep(IMAGE_STREAM_TAG_RETRY_TIMEOUT_IN_MILLIS);
                } catch (InterruptedException e) {
                    log.debug("interrupted", e);
                }
            }
            currentImageStream = client.imageStreams().withName(imageStreamName).get();
            if (currentImageStream == null) {
                continue;
            }
            ImageStreamStatus status = currentImageStream.getStatus();
            if (status == null) {
                continue;
            }
            List<NamedTagEventList> tags = status.getTags();
            if (tags == null || tags.isEmpty()) {
                continue;
            }

            // Iterate all imagestream tags and get the latest one by 'created' attribute
            TagEvent latestTag = null;

            TAG_EVENT_LIST:
            for (NamedTagEventList list : tags) {
                List<TagEvent> items = list.getItems();
                if (items == null || items.isEmpty()) {
                    continue TAG_EVENT_LIST;
                }

                for (TagEvent tag : items) {
                    latestTag = latestTag == null ? tag : newerTag(tag, latestTag);
                }
            }

            if (latestTag != null && Strings.isNotBlank(latestTag.getImage())) {
                String image = latestTag.getImage();
                log.info("Found tag on ImageStream " + imageStreamName + " tag: " + image);
                return image;
            }
        }

        // No image found, even after several retries:
        if (currentImageStream == null) {
            throw new MojoExecutionException("Could not find a current ImageStream with name " + imageStreamName + " in namespace " + namespace);
        } else {
            throw new MojoExecutionException("Could not find a tag in the ImageStream " + imageStreamName);
        }
    }

    public TagEvent newerTag(TagEvent tag1, TagEvent tag2) {
        Date tag1Date = extractDate(tag1);
        Date tag2Date = extractDate(tag2);

        if(tag1Date == null) {
            return tag2;
        }

        if(tag2Date == null) {
            return tag1;
        }

        return tag1Date.compareTo(tag2Date) > 0 ? tag1 : tag2;
    }

    private Date extractDate(TagEvent tag) {
        try {
            return new SimpleDateFormat(DATE_FORMAT).parse(tag.getCreated());
        } catch (ParseException e) {
            log.error("parsing date error : " + e.getMessage(), e);
            return null;
        } catch (NullPointerException e) {
            log.error("tag date is null : " + e.getMessage(), e);
            return null;
        }

    }
}
