<?php

namespace FlowCatalyst\Generated\Normalizer;

use Jane\Component\JsonSchemaRuntime\Reference;
use FlowCatalyst\Generated\Runtime\Normalizer\CheckArray;
use FlowCatalyst\Generated\Runtime\Normalizer\ValidatorTrait;
use Symfony\Component\Serializer\Normalizer\DenormalizerAwareInterface;
use Symfony\Component\Serializer\Normalizer\DenormalizerAwareTrait;
use Symfony\Component\Serializer\Normalizer\DenormalizerInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareTrait;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;
class RequestSummaryNormalizer implements DenormalizerInterface, NormalizerInterface, DenormalizerAwareInterface, NormalizerAwareInterface
{
    use DenormalizerAwareTrait;
    use NormalizerAwareTrait;
    use CheckArray;
    use ValidatorTrait;
    public function supportsDenormalization(mixed $data, string $type, ?string $format = null, array $context = []): bool
    {
        return $type === \FlowCatalyst\Generated\Model\RequestSummary::class;
    }
    public function supportsNormalization(mixed $data, ?string $format = null, array $context = []): bool
    {
        return is_object($data) && get_class($data) === \FlowCatalyst\Generated\Model\RequestSummary::class;
    }
    public function denormalize(mixed $data, string $type, ?string $format = null, array $context = []): mixed
    {
        $object = new \FlowCatalyst\Generated\Model\RequestSummary();
        if (null === $data || false === \is_array($data)) {
            return $object;
        }
        if (isset($data['$ref']) && !isset($data['type']) && !isset($data['properties']) && !isset($data['allOf'])) {
            return new Reference($data['$ref'], $context['document-origin']);
        }
        if (isset($data['$recursiveRef'])) {
            return new Reference($data['$recursiveRef'], $context['document-origin']);
        }
        if (\array_key_exists('bearer', $data) && \is_int($data['bearer'])) {
            $data['bearer'] = (bool) $data['bearer'];
        }
        if (\array_key_exists('signature', $data) && \is_int($data['signature'])) {
            $data['signature'] = (bool) $data['signature'];
        }
        if (\array_key_exists('bearer', $data) && $data['bearer'] !== null) {
            $object->setBearer($data['bearer']);
        }
        elseif (\array_key_exists('bearer', $data) && $data['bearer'] === null) {
            $object->setBearer(null);
        }
        if (\array_key_exists('headers', $data) && $data['headers'] !== null) {
            $values = [];
            foreach ($data['headers'] as $value) {
                $values[] = $value;
            }
            $object->setHeaders($values);
        }
        elseif (\array_key_exists('headers', $data) && $data['headers'] === null) {
            $object->setHeaders(null);
        }
        if (\array_key_exists('signature', $data) && $data['signature'] !== null) {
            $object->setSignature($data['signature']);
        }
        elseif (\array_key_exists('signature', $data) && $data['signature'] === null) {
            $object->setSignature(null);
        }
        if (\array_key_exists('signedBy', $data) && $data['signedBy'] !== null) {
            $object->setSignedBy($data['signedBy']);
        }
        elseif (\array_key_exists('signedBy', $data) && $data['signedBy'] === null) {
            $object->setSignedBy(null);
        }
        if (\array_key_exists('target', $data) && $data['target'] !== null) {
            $object->setTarget($data['target']);
        }
        elseif (\array_key_exists('target', $data) && $data['target'] === null) {
            $object->setTarget(null);
        }
        if (\array_key_exists('timestamp', $data) && $data['timestamp'] !== null) {
            $object->setTimestamp($data['timestamp']);
        }
        elseif (\array_key_exists('timestamp', $data) && $data['timestamp'] === null) {
            $object->setTimestamp(null);
        }
        if (\array_key_exists('unsignedReason', $data) && $data['unsignedReason'] !== null) {
            $object->setUnsignedReason($data['unsignedReason']);
        }
        elseif (\array_key_exists('unsignedReason', $data) && $data['unsignedReason'] === null) {
            $object->setUnsignedReason(null);
        }
        return $object;
    }
    public function normalize(mixed $data, ?string $format = null, array $context = []): array|string|int|float|bool|\ArrayObject|null
    {
        $dataArray = [];
        $dataArray['bearer'] = $data->getBearer();
        if ($data->isInitialized('headers') && null !== $data->getHeaders()) {
            $values = [];
            foreach ($data->getHeaders() as $value) {
                $values[] = $value;
            }
            $dataArray['headers'] = $values;
        }
        $dataArray['signature'] = $data->getSignature();
        if ($data->isInitialized('signedBy') && null !== $data->getSignedBy()) {
            $dataArray['signedBy'] = $data->getSignedBy();
        }
        if ($data->isInitialized('target') && null !== $data->getTarget()) {
            $dataArray['target'] = $data->getTarget();
        }
        if ($data->isInitialized('timestamp') && null !== $data->getTimestamp()) {
            $dataArray['timestamp'] = $data->getTimestamp();
        }
        if ($data->isInitialized('unsignedReason') && null !== $data->getUnsignedReason()) {
            $dataArray['unsignedReason'] = $data->getUnsignedReason();
        }
        return $dataArray;
    }
    public function getSupportedTypes(?string $format = null): array
    {
        return [\FlowCatalyst\Generated\Model\RequestSummary::class => false];
    }
}